<?php

use App\Models\Account;
use App\Models\Due;
use App\Models\Loan;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;

uses(RefreshDatabase::class);

beforeEach(function () {
    // Thursday 29 Oct 2026, 10:00 in Kolkata.
    $this->travelTo(CarbonImmutable::parse('2026-10-29 04:30:00', 'UTC'));
    $this->user = User::factory()->create(['timezone' => 'Asia/Kolkata']);
    Sanctum::actingAs($this->user);
    $this->account = Account::factory()->for($this->user)->create(['is_primary' => true]);
});

function loanInput(array $overrides = []): array
{
    return array_merge([
        'name' => 'Car loan',
        'type' => 'car',
        'principal' => 100_000_000,
        'interest_rate' => 12,
        'tenure_months' => 12,
        'first_emi_date' => '2026-08-05',
        'account_id' => test()->account->id,
    ], $overrides);
}

it('creates a loan with its computed EMI and repayment progress', function () {
    $this->postJson('/api/v1/loans', loanInput())
        ->assertCreated()
        ->assertJsonPath('data.emi_amount', null)
        ->assertJsonPath('data.emi', 8_884_900)
        ->assertJsonPath('data.status', 'active')
        ->assertJsonPath('data.auto_debit', true)
        // Aug, Sep and Oct are behind us.
        ->assertJsonPath('data.emis_paid', 3)
        ->assertJsonPath('data.emis_left', 9)
        ->assertJsonPath('data.outstanding', 76_107_965)
        ->assertJsonPath('data.next_emi_date', '2026-11-05')
        ->assertJsonPath('data.last_emi_date', '2027-07-05');
});

it('uses the bank EMI when given and rejects one that never repays', function () {
    $this->postJson('/api/v1/loans', loanInput(['emi_amount' => 9_000_000]))
        ->assertCreated()
        ->assertJsonPath('data.emi', 9_000_000);

    $this->postJson('/api/v1/loans', loanInput(['emi_amount' => 1_000_000]))
        ->assertJsonValidationErrors(['emi_amount' => 'first month']);
});

it('creates the EMI dues inside the 45-day window only', function () {
    $id = $this->postJson('/api/v1/loans', loanInput())->json('data.id');

    $dues = Due::query()->where('source_id', $id)->orderBy('due_date')->get();
    expect($dues->pluck('due_date')->map->toDateString()->all())->toBe(['2026-11-05', '2026-12-05'])
        ->and($dues->pluck('period')->all())->toBe(['2026-11', '2026-12'])
        ->and($dues->first()->title)->toBe('Car loan EMI')
        ->and($dues->first()->amount)->toBe(8_884_900);
});

it('clamps EMIs on the 31st to short months', function () {
    $id = $this->postJson('/api/v1/loans', loanInput(['first_emi_date' => '2027-01-31', 'tenure_months' => 4]))->json('data.id');

    $dates = collect($this->getJson("/api/v1/loans/{$id}/schedule")->json('data'))->pluck('due_date')->all();
    expect($dates)->toBe(['2027-01-31', '2027-02-28', '2027-03-31', '2027-04-30']);
});

it('returns the amortisation schedule ending at zero', function () {
    $id = $this->postJson('/api/v1/loans', loanInput())->json('data.id');

    $rows = $this->getJson("/api/v1/loans/{$id}/schedule")->assertOk()->json('data');
    expect($rows)->toHaveCount(12)
        ->and($rows[0])->toMatchArray(['installment' => 1, 'due_date' => '2026-08-05', 'interest' => 1_000_000, 'paid' => true])
        ->and($rows[3]['paid'])->toBeFalse()
        ->and(end($rows)['outstanding'])->toBe(0);
});

it('rebuilds open dues when a loan is edited, leaving paid ones alone', function () {
    $id = $this->postJson('/api/v1/loans', loanInput())->json('data.id');
    $november = Due::query()->where('source_id', $id)->where('period', '2026-11')->first();
    $this->postJson("/api/v1/dues/{$november->id}/pay")->assertOk();

    $this->putJson("/api/v1/loans/{$id}", loanInput(['emi_amount' => 9_500_000]))->assertOk();

    expect($november->refresh()->amount)->toBe(8_884_900)
        ->and(Due::query()->where('source_id', $id)->where('period', '2026-12')->value('amount'))->toBe(9_500_000);
});

it('closes a loan early and drops its unpaid dues', function () {
    $id = $this->postJson('/api/v1/loans', loanInput())->json('data.id');

    $this->postJson("/api/v1/loans/{$id}/close")
        ->assertOk()
        ->assertJsonPath('data.status', 'closed')
        ->assertJsonPath('data.closed_on', '2026-10-29')
        ->assertJsonPath('data.outstanding', 0)
        ->assertJsonPath('data.next_emi_date', null);

    expect(Due::query()->where('source_type', 'loan')->count())->toBe(0);
    $this->getJson('/api/v1/dues')->assertJsonCount(0, 'data');
});

it('closes a loan by itself once its last EMI has passed', function () {
    $loan = Loan::factory()->for($this->user)->for($this->account)->create([
        'principal' => 10_000_000, 'interest_rate' => 0, 'tenure_months' => 3, 'first_emi_date' => '2026-06-05',
    ]);

    $this->getJson('/api/v1/dues')->assertJsonCount(0, 'data');

    expect($loan->refresh()->status->value)->toBe('closed')
        ->and($loan->closed_on->toDateString())->toBe('2026-08-05');
});

it('keeps paid EMIs as history when a loan is deleted', function () {
    $id = $this->postJson('/api/v1/loans', loanInput())->json('data.id');
    $november = Due::query()->where('source_id', $id)->where('period', '2026-11')->first();
    $this->postJson("/api/v1/dues/{$november->id}/pay")->assertOk();

    $this->deleteJson("/api/v1/loans/{$id}")->assertNoContent();

    expect(Due::query()->pluck('period')->all())->toBe(['2026-11']);
    $this->getJson('/api/v1/dues')
        ->assertJsonCount(1, 'data')
        ->assertJsonPath('data.0.status', 'paid')
        ->assertJsonPath('data.0.account_id', null);
});

it("hides other users' loans", function () {
    $theirs = Loan::factory()->create();

    $this->getJson("/api/v1/loans/{$theirs->id}")->assertNotFound();
    $this->getJson("/api/v1/loans/{$theirs->id}/schedule")->assertNotFound();
    $this->postJson('/api/v1/loans', loanInput(['account_id' => $theirs->account_id]))
        ->assertJsonValidationErrors(['account_id']);
});
