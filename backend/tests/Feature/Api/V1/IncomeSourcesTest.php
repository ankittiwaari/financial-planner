<?php

use App\Models\Account;
use App\Models\IncomeSource;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;

uses(RefreshDatabase::class);

beforeEach(function () {
    // Thursday 29 Oct 2026, 10:00 in Kolkata.
    $this->travelTo(\Carbon\CarbonImmutable::parse('2026-10-29 04:30:00', 'UTC'));
    $this->user = User::factory()->create(['timezone' => 'Asia/Kolkata']);
    Sanctum::actingAs($this->user);
    $this->account = Account::factory()->for($this->user)->create(['is_primary' => true]);
});

function salaryInput(array $overrides = []): array
{
    return array_merge([
        'name' => 'Salary - Acme',
        'account_id' => test()->account->id,
        'amount' => 12_000_000,
        'pay_rule' => 'last_working_day',
    ], $overrides);
}

it('creates a source with defaults and its next pay date', function () {
    $this->postJson('/api/v1/income-sources', salaryInput())
        ->assertCreated()
        ->assertJsonPath('data.pay_day', null)
        ->assertJsonPath('data.weekend_shift', 'before')
        ->assertJsonPath('data.auto_credit', true)
        ->assertJsonPath('data.is_variable', false)
        ->assertJsonPath('data.active_from', '2026-10-29')
        ->assertJsonPath('data.next_pay_date', '2026-10-30') // 31st is a Saturday
        ->assertJsonPath('data.last_credited_period', null);
});

it('computes next pay dates for fixed days and weekend shifts', function (array $input, string $expected) {
    $this->postJson('/api/v1/income-sources', salaryInput($input))
        ->assertCreated()
        ->assertJsonPath('data.next_pay_date', $expected);
})->with([
    'later this month' => [['pay_rule' => 'fixed_day', 'pay_day' => 30], '2026-10-30'],
    'already passed this month' => [['pay_rule' => 'fixed_day', 'pay_day' => 7], '2026-11-06'], // 7 Nov is a Saturday
    'weekend moved after' => [['pay_rule' => 'fixed_day', 'pay_day' => 1, 'weekend_shift' => 'after'], '2026-11-02'],
    'last day, moved before' => [['pay_rule' => 'last_day'], '2026-10-30'],
    'starts in the future' => [['pay_rule' => 'last_working_day', 'active_from' => '2027-03-01'], '2027-03-31'],
]);

it('requires a pay day for fixed_day and drops it otherwise', function () {
    $this->postJson('/api/v1/income-sources', salaryInput(['pay_rule' => 'fixed_day']))
        ->assertJsonValidationErrors(['pay_day' => 'day of the month']);

    $this->postJson('/api/v1/income-sources', salaryInput(['pay_day' => 5]))
        ->assertCreated()
        ->assertJsonPath('data.pay_day', null);
});

it('logs a credit dated on payday into the salary category', function () {
    $source = IncomeSource::factory()->for($this->user)->for($this->account)->create();

    $this->postJson("/api/v1/income-sources/{$source->id}/credits", ['period' => '2026-10'])
        ->assertCreated()
        ->assertJsonPath('data.direction', 'credit')
        ->assertJsonPath('data.amount', 12_000_000)
        ->assertJsonPath('data.occurred_on', '2026-10-30')
        ->assertJsonPath('data.account_id', $this->account->id)
        ->assertJsonPath('data.category_id', $this->user->categories()->where('name', 'Salary')->value('id'))
        ->assertJsonPath('data.source_type', 'income_source')
        ->assertJsonPath('data.source_id', $source->id)
        ->assertJsonPath('data.period', '2026-10');

    $this->getJson("/api/v1/accounts/{$this->account->id}")->assertJsonPath('data.balance', 12_000_000);
    $this->getJson('/api/v1/income-sources')->assertJsonPath('data.0.last_credited_period', '2026-10');
});

it('takes an overridden amount and date for a variable salary', function () {
    $source = IncomeSource::factory()->for($this->user)->for($this->account)->create(['is_variable' => true]);

    $this->postJson("/api/v1/income-sources/{$source->id}/credits", [
        'period' => '2026-10', 'amount' => 11_250_000, 'occurred_on' => '2026-10-29',
    ])->assertCreated()
        ->assertJsonPath('data.amount', 11_250_000)
        ->assertJsonPath('data.occurred_on', '2026-10-29');
});

it('refuses a second credit for the same period', function () {
    $source = IncomeSource::factory()->for($this->user)->for($this->account)->create();
    $this->postJson("/api/v1/income-sources/{$source->id}/credits", ['period' => '2026-10'])->assertCreated();

    $this->postJson("/api/v1/income-sources/{$source->id}/credits", ['period' => '2026-10'])
        ->assertStatus(409)
        ->assertJsonPath('message', 'Salary - Acme is already logged for Oct 2026.');

    $this->postJson("/api/v1/income-sources/{$source->id}/credits", ['period' => '2026-11'])->assertCreated();
    $this->postJson("/api/v1/income-sources/{$source->id}/credits", ['period' => '2026-13'])->assertJsonValidationErrors(['period']);
});

it('refuses a credit into an archived account', function () {
    $source = IncomeSource::factory()->for($this->user)->for($this->account)->create();
    $this->account->forceFill(['archived_at' => now()])->save();

    $this->postJson("/api/v1/income-sources/{$source->id}/credits", ['period' => '2026-10'])
        ->assertJsonValidationErrors(['account_id']);
});

it('keeps past credits as plain income when a source is deleted', function () {
    $source = IncomeSource::factory()->for($this->user)->for($this->account)->create();
    $credit = $this->postJson("/api/v1/income-sources/{$source->id}/credits", ['period' => '2026-10'])->json('data.id');

    $this->deleteJson("/api/v1/income-sources/{$source->id}")->assertNoContent();

    $transaction = Transaction::find($credit);
    expect($transaction)->not->toBeNull()
        ->and($transaction->source_type)->toBeNull()
        ->and($transaction->source_id)->toBeNull()
        ->and($transaction->period)->toBe('2026-10');
});

it("hides other users' income sources", function () {
    $theirs = IncomeSource::factory()->create();

    $this->getJson("/api/v1/income-sources/{$theirs->id}")->assertNotFound();
    $this->postJson("/api/v1/income-sources/{$theirs->id}/credits", ['period' => '2026-10'])->assertNotFound();
    $this->postJson('/api/v1/income-sources', salaryInput(['account_id' => $theirs->account_id]))
        ->assertJsonValidationErrors(['account_id']);
});
