<?php

use App\Models\Account;
use App\Models\CreditCard;
use App\Models\Due;
use App\Models\Loan;
use App\Models\RecurringPayment;
use App\Models\Transaction;
use App\Models\User;
use App\Services\DuesGenerator;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;

uses(RefreshDatabase::class);

beforeEach(function () {
    // Thursday 29 Oct 2026, 10:00 in Kolkata.
    $this->travelTo(CarbonImmutable::parse('2026-10-29 04:30:00', 'UTC'));
    $this->user = User::factory()->create(['timezone' => 'Asia/Kolkata']);
    Sanctum::actingAs($this->user);
    $this->account = Account::factory()->for($this->user)->create(['is_primary' => true, 'opening_balance' => 50_000_000]);
});

/** A home loan (EMI on the 5th), a card (bill due the 17th) and rent (the 1st). */
function seedObligations(): void
{
    $user = test()->user;
    $account = test()->account;
    Loan::factory()->for($user)->for($account)->create();
    CreditCard::factory()->for($user)->create(['pay_from_account_id' => $account->id]);
    RecurringPayment::factory()->for($user)->for($account)->create();
    app(DuesGenerator::class)->forUser($user);
}

it('lists the next 45 days of dues in date order', function () {
    seedObligations();

    $response = $this->getJson('/api/v1/dues')->assertOk();

    expect(collect($response->json('data'))->map(fn ($d) => [$d['due_date'], $d['source_type'], $d['status']])->all())->toBe([
        ['2026-11-01', 'recurring_payment', 'upcoming'],
        ['2026-11-05', 'loan', 'upcoming'],
        ['2026-11-17', 'card_statement', 'upcoming'],
        ['2026-12-01', 'recurring_payment', 'upcoming'],
        ['2026-12-05', 'loan', 'upcoming'],
    ]);
    $response->assertJsonPath('data.1.title', 'HDFC Home Loan EMI')
        ->assertJsonPath('data.1.amount', 4_339_100)
        ->assertJsonPath('data.1.auto_debit', true)
        ->assertJsonPath('data.1.account_id', $this->account->id);
});

it('never duplicates dues however often it runs', function () {
    seedObligations();

    $this->getJson('/api/v1/dues')->assertJsonCount(5, 'data');
    $this->getJson('/api/v1/dues')->assertJsonCount(5, 'data');
    $this->artisan('dues:generate')->assertSuccessful();

    expect(Due::query()->count())->toBe(5);
});

it('rolls the window forward and reports missed dues as overdue', function () {
    seedObligations();
    $this->getJson('/api/v1/dues');

    $this->travelTo(CarbonImmutable::parse('2026-11-20 04:30:00', 'UTC')); // window now ends 4 Jan

    $dues = collect($this->getJson('/api/v1/dues')->assertOk()->json('data'))
        ->map(fn ($d) => "{$d['due_date']} {$d['source_type']} {$d['status']}")
        ->all();
    expect($dues)->toBe([
        '2026-11-01 recurring_payment overdue',
        '2026-11-05 loan overdue',
        '2026-11-17 card_statement overdue',
        '2026-12-01 recurring_payment upcoming',
        '2026-12-05 loan upcoming',
        '2026-12-17 card_statement upcoming',
        '2027-01-01 recurring_payment upcoming',
    ]);
});

it('filters by status', function () {
    seedObligations();
    $ids = collect($this->getJson('/api/v1/dues')->json('data'))->pluck('id');
    $this->postJson("/api/v1/dues/{$ids[0]}/pay")->assertOk();
    $this->postJson("/api/v1/dues/{$ids[1]}/skip")->assertOk();

    $this->getJson('/api/v1/dues?status=paid')->assertJsonCount(1, 'data')->assertJsonPath('data.0.id', $ids[0]);
    $this->getJson('/api/v1/dues?status=skipped')->assertJsonCount(1, 'data')->assertJsonPath('data.0.id', $ids[1]);
    $this->getJson('/api/v1/dues?status=upcoming')->assertJsonCount(3, 'data');
    $this->getJson('/api/v1/dues?status=overdue')->assertJsonCount(0, 'data');
    $this->getJson('/api/v1/dues?status=late')->assertJsonValidationErrors(['status']);
});

it('pays a due by booking a debit from its account', function () {
    seedObligations();
    $emi = Due::query()->where('source_type', 'loan')->where('period', '2026-11')->first();

    $this->postJson("/api/v1/dues/{$emi->id}/pay")
        ->assertOk()
        ->assertJsonPath('data.status', 'paid')
        ->assertJsonPath('data.paid_on', '2026-10-29')
        ->assertJsonPath('data.paid_amount', 4_339_100);

    $transaction = Transaction::query()->sole();
    expect($transaction->direction->value)->toBe('debit')
        ->and($transaction->amount)->toBe(4_339_100)
        ->and($transaction->account_id)->toBe($this->account->id)
        ->and($transaction->category->name)->toBe('Loan EMI')
        ->and($transaction->description)->toBe('HDFC Home Loan EMI')
        ->and($transaction->source_type)->toBe('due')
        ->and($transaction->source_id)->toBe($emi->id);
    $this->getJson("/api/v1/accounts/{$this->account->id}")->assertJsonPath('data.balance', 50_000_000 - 4_339_100);
});

it('pays a different amount, on another date, from another account', function () {
    seedObligations();
    $other = Account::factory()->for($this->user)->create();
    $rent = Due::query()->where('source_type', 'recurring_payment')->orderBy('due_date')->first();

    $this->postJson("/api/v1/dues/{$rent->id}/pay", ['amount' => 2_400_000, 'paid_on' => '2026-10-31', 'account_id' => $other->id])
        ->assertOk()
        ->assertJsonPath('data.paid_amount', 2_400_000)
        ->assertJsonPath('data.paid_on', '2026-10-31');

    expect(Transaction::query()->sole()->account_id)->toBe($other->id);
});

it('refuses to pay twice, from an archived account, or a zero estimate without an amount', function () {
    seedObligations();
    $dues = Due::query()->orderBy('due_date')->get()->keyBy('source_type');
    $archived = Account::factory()->for($this->user)->archived()->create();

    $this->postJson("/api/v1/dues/{$dues['loan']->id}/pay", ['account_id' => $archived->id])->assertJsonValidationErrors(['account_id']);
    $this->postJson("/api/v1/dues/{$dues['card_statement']->id}/pay")->assertJsonValidationErrors(['amount']);

    $this->postJson("/api/v1/dues/{$dues['loan']->id}/pay")->assertOk();
    $this->postJson("/api/v1/dues/{$dues['loan']->id}/pay")
        ->assertStatus(409)
        ->assertJsonPath('message', 'HDFC Home Loan EMI is already marked paid.');
    expect(Transaction::query()->count())->toBe(1);
});

it('reopens a paid due and puts the money back', function () {
    seedObligations();
    $emi = Due::query()->where('source_type', 'loan')->orderBy('due_date')->first();
    $this->postJson("/api/v1/dues/{$emi->id}/pay")->assertOk();

    $this->postJson("/api/v1/dues/{$emi->id}/reopen")
        ->assertOk()
        ->assertJsonPath('data.status', 'upcoming')
        ->assertJsonPath('data.paid_on', null)
        ->assertJsonPath('data.transaction_id', null);

    expect(Transaction::query()->count())->toBe(0);
    $this->getJson("/api/v1/accounts/{$this->account->id}")->assertJsonPath('data.balance', 50_000_000);
    $this->postJson("/api/v1/dues/{$emi->id}/reopen")->assertStatus(409);
});

it('reopens the due when its payment transaction is deleted', function () {
    seedObligations();
    $emi = Due::query()->where('source_type', 'loan')->orderBy('due_date')->first();
    $transactionId = $this->postJson("/api/v1/dues/{$emi->id}/pay")->json('data.transaction_id');

    $this->deleteJson("/api/v1/transactions/{$transactionId}")->assertNoContent();

    expect($emi->refresh()->status->value)->toBe('upcoming')
        ->and(Transaction::query()->count())->toBe(0);
});

it('skips a due and can undo the skip', function () {
    seedObligations();
    $rent = Due::query()->where('source_type', 'recurring_payment')->orderBy('due_date')->first();

    $this->postJson("/api/v1/dues/{$rent->id}/skip")->assertOk()->assertJsonPath('data.status', 'skipped');
    $this->postJson("/api/v1/dues/{$rent->id}/pay")->assertStatus(409);
    $this->postJson("/api/v1/dues/{$rent->id}/reopen")->assertOk()->assertJsonPath('data.status', 'upcoming');
});

it("hides other users' dues", function () {
    $theirLoan = Loan::factory()->create();
    $this->artisan('dues:generate')->assertSuccessful();
    $theirs = Due::query()->where('user_id', $theirLoan->user_id)->first();

    $this->getJson('/api/v1/dues')->assertJsonCount(0, 'data');
    $this->getJson("/api/v1/dues/{$theirs->id}")->assertNotFound();
    $this->postJson("/api/v1/dues/{$theirs->id}/pay")->assertNotFound();
});
