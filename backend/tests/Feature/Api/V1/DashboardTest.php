<?php

use App\Models\Account;
use App\Models\Due;
use App\Models\IncomeSource;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;

uses(RefreshDatabase::class);

beforeEach(function () {
    $this->user = User::factory()->create(['timezone' => 'Asia/Kolkata']);
    Sanctum::actingAs($this->user);
    $this->account = Account::factory()->for($this->user)->create(['is_primary' => true]);
    $this->category = fn (string $name) => $this->user->categories()->where('name', $name)->value('id');
});

/** 10:00 in Kolkata on the given date. */
function localDay(string $date): void
{
    test()->travelTo(CarbonImmutable::parse("{$date} 04:30:00", 'UTC'));
}

function logSpend(int $amount, string $on, string $category = 'Groceries'): void
{
    test()->postJson('/api/v1/transactions', [
        'account_id' => test()->account->id, 'category_id' => (test()->category)($category),
        'direction' => 'debit', 'amount' => $amount, 'occurred_on' => $on,
    ])->assertCreated();
}

function salarySource(array $overrides = []): IncomeSource
{
    // ₹1,20,000 on the last working day: Fri 30 Oct, Mon 30 Nov 2026.
    return IncomeSource::factory()->for(test()->user)->for(test()->account)->create($overrides);
}

function dueIdFor(string $title, string $period): int
{
    return Due::query()->where('title', $title)->where('period', $period)->value('id');
}

/*
 * Hand-worked cycle: salary to salary, Fri 30 Oct to Sun 29 Nov 2026, viewed on Tue 3 Nov (27 days left).
 *
 *   Income     Oct salary credited                     1,20,000
 *              interest credited 1 Nov                     5,000
 *              Ravi paid back 3 Nov                        4,000   → 1,29,000
 *   Dues       Home loan EMI 5 Nov (open)                 31,000
 *              Card bill due 17 Nov (entered)             18,950
 *              Rent 1 Nov (paid)                           7,500
 *              Netflix 25 Nov (open)                         649
 *              Netflix 25 Oct (overdue, carried in)          649
 *              SIP 10 Nov (skipped)                            0   → 58,748
 *   Spent      groceries 2 Nov + food 31 Oct               3,300   (rent payment not counted twice; 29 Oct is last cycle)
 *   Lent       to Ravi 2 Nov                              10,000
 *
 *   Purchasing power = 1,29,000 − 58,748 − 3,300 − 10,000 = 56,952 → ₹2,109.33 a day for 27 days
 */
it('matches a hand-worked purchasing power', function () {
    localDay('2026-10-20');
    $salary = salarySource();
    $this->postJson('/api/v1/loans', [
        'name' => 'Home loan', 'type' => 'home', 'principal' => 400_000_000, 'interest_rate' => 8.5,
        'tenure_months' => 240, 'emi_amount' => 3_100_000, 'first_emi_date' => '2026-06-05', 'account_id' => $this->account->id,
    ])->assertCreated();
    $card = $this->postJson('/api/v1/credit-cards', [
        'nickname' => 'ICICI', 'statement_day' => 28, 'due_day' => 17, 'pay_from_account_id' => $this->account->id,
    ])->json('data.id');
    foreach ([['Rent', 750_000, 1], ['Netflix', 64_900, 25], ['SIP', 500_000, 10]] as [$name, $amount, $day]) {
        $this->postJson('/api/v1/recurring-payments', [
            'name' => $name, 'amount' => $amount, 'frequency' => 'monthly', 'day' => $day,
            'active_from' => '2026-10-01', 'account_id' => $this->account->id,
        ])->assertCreated();
    }
    $this->getJson('/api/v1/dues')->assertOk(); // the daily job, as it would have run on 20 Oct

    localDay('2026-11-03');
    $this->postJson("/api/v1/income-sources/{$salary->id}/credits", ['period' => '2026-10'])->assertCreated();
    $this->postJson('/api/v1/transactions', [
        'account_id' => $this->account->id, 'category_id' => ($this->category)('Interest'),
        'direction' => 'credit', 'amount' => 500_000, 'occurred_on' => '2026-11-01',
    ])->assertCreated();
    $this->postJson("/api/v1/credit-cards/{$card}/statements", ['period' => '2026-10', 'total_due' => 1_895_000])->assertOk();
    $this->postJson('/api/v1/dues/'.dueIdFor('Rent', '2026-11').'/pay', ['paid_on' => '2026-11-01'])->assertOk();
    $this->postJson('/api/v1/dues/'.dueIdFor('SIP', '2026-11').'/skip')->assertOk();
    logSpend(230_000, '2026-11-02');
    logSpend(100_000, '2026-10-31', 'Food & dining');
    logSpend(999_900, '2026-10-29'); // the day before payday: last cycle
    $ravi = $this->postJson('/api/v1/lendings', [
        'person_name' => 'Ravi', 'amount' => 1_000_000, 'lent_on' => '2026-11-02', 'account_id' => $this->account->id,
    ])->json('data.id');
    $this->postJson("/api/v1/lendings/{$ravi}/repayments", ['amount' => 400_000, 'account_id' => $this->account->id])->assertCreated();

    $data = $this->getJson('/api/v1/dashboard')->assertOk()->json('data');

    expect($data['cycle'])->toBe([
        'start' => '2026-10-30', 'end' => '2026-11-29', 'mode' => 'payday',
        'anchor_income_source_id' => $salary->id, 'days_total' => 31, 'days_left' => 27,
    ])
        ->and(collect($data['income'])->except('sources')->all())
        ->toBe(['expected' => 12_000_000, 'credited' => 12_000_000, 'other' => 900_000, 'total' => 12_900_000])
        ->and($data['reserved_for_dues'])->toBe([
            'total' => 5_874_800, 'emis' => 3_100_000, 'cards' => 1_895_000, 'recurring' => 879_800,
            'overdue' => 64_900, 'paid' => 750_000, 'open' => 5_124_800,
        ])
        ->and($data['spent_so_far'])->toBe(330_000)
        ->and($data['lent_out'])->toBe(1_000_000)
        ->and($data['purchasing_power'])->toBe(5_695_200)
        ->and($data['per_day_left'])->toBe(210_933)
        ->and($data['receivables'])->toBe(['total_lent_outstanding' => 600_000, 'people' => 1, 'overdue' => 0])
        ->and(collect($data['next_dues'])->map(fn ($d) => "{$d['due_date']} {$d['title']}")->all())->toBe([
            '2026-11-05 Home loan EMI',
            '2026-11-17 ICICI bill',
            '2026-11-25 Netflix',
            '2026-12-01 Rent',
            '2026-12-05 Home loan EMI',
        ])
        ->and(collect($data['alerts'])->pluck('type')->all())->toBe(['overdue_dues']);
});

it('counts expected salary until it is logged, and asks for it once payday has passed', function () {
    localDay('2026-11-03');
    salarySource(['is_variable' => true]);
    logSpend(100_000, '2026-11-01');

    $data = $this->getJson('/api/v1/dashboard')->json('data');

    expect($data['income']['total'])->toBe(12_000_000)
        ->and($data['income']['credited'])->toBe(0)
        ->and($data['income']['sources'][0])->toMatchArray(['period' => '2026-10', 'pay_date' => '2026-10-30', 'credited' => null, 'awaiting' => true])
        ->and($data['purchasing_power'])->toBe(11_900_000)
        ->and($data['alerts'][0])->toBe([
            'type' => 'confirm_income',
            'message' => 'Log Salary - Acme for Oct 2026 and confirm the amount (payday was 30 Oct)',
        ]);
});

it('starts a new cycle on payday itself', function () {
    localDay('2026-11-30');
    salarySource();

    $this->getJson('/api/v1/dashboard')
        ->assertJsonPath('data.cycle.start', '2026-11-30')
        ->assertJsonPath('data.cycle.end', '2026-12-30') // 31 Dec is a Thursday payday
        ->assertJsonPath('data.cycle.days_left', 31);
});

it('anchors on the largest salary and uses calendar months without one or when asked', function () {
    localDay('2026-11-03');
    $this->getJson('/api/v1/dashboard')
        ->assertJsonPath('data.cycle.mode', 'calendar')
        ->assertJsonPath('data.cycle.start', '2026-11-01')
        ->assertJsonPath('data.cycle.end', '2026-11-30')
        ->assertJsonPath('data.purchasing_power', 0);

    salarySource(['name' => 'Side gig', 'amount' => 2_000_000, 'pay_rule' => 'fixed_day', 'pay_day' => 15]);
    $main = salarySource();
    $this->getJson('/api/v1/dashboard')->assertJsonPath('data.cycle.anchor_income_source_id', $main->id);

    $this->user->forceFill(['cycle_mode' => 'calendar'])->save();
    $this->getJson('/api/v1/dashboard')
        ->assertJsonPath('data.cycle.start', '2026-11-01')
        ->assertJsonPath('data.income.total', 14_000_000); // side gig on 15 Nov + salary on 30 Nov
});

it('reminds to enter a card bill once its statement date has passed', function () {
    localDay('2026-11-03');
    salarySource();
    $this->postJson('/api/v1/credit-cards', [
        'nickname' => 'HDFC', 'last4' => '1234', 'statement_day' => 1, 'due_day' => 20, 'pay_from_account_id' => $this->account->id,
    ])->assertCreated();

    $alerts = collect($this->getJson('/api/v1/dashboard')->json('data.alerts'));

    expect($alerts->pluck('type')->all())->toBe(['confirm_income', 'enter_card_bill'])
        ->and($alerts[1]['message'])->toBe('Enter the HDFC ••1234 bill (statement generated 1 Nov)');
});

it('goes negative when commitments exceed income, without a negative daily allowance', function () {
    localDay('2026-11-03');
    salarySource(['amount' => 1_000_000]);
    logSpend(1_500_000, '2026-11-02');

    $this->getJson('/api/v1/dashboard')
        ->assertJsonPath('data.purchasing_power', -500_000)
        ->assertJsonPath('data.per_day_left', 0);
});

it('counts a due on the last day of the cycle', function () {
    localDay('2026-11-03');
    salarySource(); // cycle 30 Oct – 29 Nov
    $this->postJson('/api/v1/recurring-payments', [
        'name' => 'Insurance', 'amount' => 300_000, 'frequency' => 'monthly', 'day' => 29, 'account_id' => $this->account->id,
    ])->assertCreated();

    $this->getJson('/api/v1/dashboard')->assertJsonPath('data.reserved_for_dues.recurring', 300_000);
});
