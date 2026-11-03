<?php

use App\Models\Account;
use App\Models\Due;
use App\Models\IncomeSource;
use App\Models\JobRun;
use App\Models\Transaction;
use App\Models\User;
use Carbon\CarbonImmutable;
use Carbon\CarbonPeriod;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;

uses(RefreshDatabase::class);

beforeEach(function () {
    $this->user = User::factory()->create(['timezone' => 'Asia/Kolkata']);
    Sanctum::actingAs($this->user);
    $this->account = Account::factory()->for($this->user)->create(['is_primary' => true]);
});

/** 00:30 in Kolkata on the given date: the first hourly run of the day. */
function runAutomationOn(string $date): void
{
    test()->travelTo(CarbonImmutable::parse("{$date} 00:30:00", 'Asia/Kolkata'));
    test()->artisan('automation:run')->assertSuccessful();
}

function autoSalary(array $overrides = []): IncomeSource
{
    // ₹1,20,000 on the last working day: Fri 30 Oct, Mon 30 Nov 2026.
    return IncomeSource::factory()->for(test()->user)->for(test()->account)->create(['auto_credit' => true, ...$overrides]);
}

/** @return list<string> "date direction amount description" for every transaction, oldest first */
function ledgerLines(): array
{
    return Transaction::query()->orderBy('occurred_on')->orderBy('id')->get()
        ->map(fn (Transaction $t) => "{$t->occurred_on} {$t->direction->value} {$t->amount} {$t->description}")
        ->all();
}

it('produces the right ledger over a simulated two months', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    autoSalary();
    autoSalary(['name' => 'Freelance', 'amount' => 3_000_000, 'is_variable' => true, 'pay_rule' => 'fixed_day', 'pay_day' => 5, 'weekend_shift' => 'none']);
    $this->postJson('/api/v1/loans', [
        'name' => 'Home loan', 'type' => 'home', 'principal' => 400_000_000, 'interest_rate' => 8.5, 'tenure_months' => 240,
        'emi_amount' => 3_100_000, 'first_emi_date' => '2026-06-05', 'account_id' => $this->account->id, 'auto_debit' => true,
    ])->assertCreated();
    $this->postJson('/api/v1/recurring-payments', [
        'name' => 'SIP', 'amount' => 500_000, 'frequency' => 'monthly', 'day' => 10, 'auto_debit' => true,
        'active_from' => '2026-10-01', 'account_id' => $this->account->id,
    ])->assertCreated();
    $this->postJson('/api/v1/recurring-payments', [
        'name' => 'Rent', 'amount' => 2_500_000, 'frequency' => 'monthly', 'day' => 1,
        'active_from' => '2026-10-01', 'account_id' => $this->account->id,
    ])->assertCreated();
    $this->postJson('/api/v1/credit-cards', [
        'nickname' => 'ICICI', 'statement_day' => 28, 'due_day' => 17, 'pay_from_account_id' => $this->account->id, 'estimate_mode' => 'fixed', 'fixed_estimate' => 1_000_000,
    ])->assertCreated();

    foreach (CarbonPeriod::create('2026-10-21', '2026-12-19') as $day) {
        runAutomationOn($day->toDateString());
    }
    runAutomationOn('2026-12-19'); // a second run the same day does nothing

    expect(ledgerLines())->toBe([
        '2026-10-30 credit 12000000 Salary - Acme',
        '2026-11-05 credit 3000000 Freelance',
        '2026-11-05 debit 3100000 Home loan EMI',
        '2026-11-10 debit 500000 SIP',
        '2026-11-30 credit 12000000 Salary - Acme',
        '2026-12-05 credit 3000000 Freelance',
        '2026-12-05 debit 3100000 Home loan EMI',
        '2026-12-10 debit 500000 SIP',
    ]);
    expect(Transaction::query()->where('is_auto', false)->count())->toBe(0)
        ->and(Transaction::query()->whereNotNull('confirmed_at')->count())->toBe(0);

    // Rent and card bills are never auto-paid: they wait, now overdue.
    expect(Due::query()->where('status', 'upcoming')->where('due_date', '<', '2026-12-19')->orderBy('due_date')->pluck('title')->all())
        ->toBe(['Rent', 'ICICI bill', 'Rent', 'ICICI bill']);

    $this->getJson("/api/v1/accounts/{$this->account->id}")
        ->assertJsonPath('data.balance', 2 * 12_000_000 + 2 * 3_000_000 - 2 * 3_100_000 - 2 * 500_000);

    // 61 scheduled days, plus the set-up day's run on its first request.
    expect(JobRun::query()->count())->toBe(62 * 3)
        ->and(JobRun::query()->where('status', '!=', 'ok')->count())->toBe(0)
        ->and(JobRun::query()->where('job', 'post_salary')->whereDate('run_for_date', '2026-10-30')->value('counts'))
        ->toMatchArray(['posted' => 1]);
});

it('never back-fills paydays from before the salary was added', function () {
    test()->travelTo(CarbonImmutable::parse('2026-11-03 10:00:00', 'Asia/Kolkata'));
    autoSalary(); // payday was 30 Oct

    runAutomationOn('2026-11-03');

    expect(Transaction::query()->count())->toBe(0);
});

it('catches up on a payday missed while the scheduler was down, within a week', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    autoSalary();

    runAutomationOn('2026-11-05'); // 6 days late
    expect(ledgerLines())->toBe(['2026-10-30 credit 12000000 Salary - Acme']);
});

it('gives up on a payday missed by more than a week', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    autoSalary();

    runAutomationOn('2026-11-07'); // 8 days late

    expect(Transaction::query()->count())->toBe(0);
});

it('leaves salaries logged by hand alone, and skips sources without auto credit', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    $salary = autoSalary();
    autoSalary(['name' => 'Rental income', 'auto_credit' => false]);
    $this->postJson("/api/v1/income-sources/{$salary->id}/credits", ['period' => '2026-10', 'amount' => 11_500_000])->assertCreated();

    runAutomationOn('2026-10-30');

    expect(ledgerLines())->toBe(['2026-10-30 credit 11500000 Salary - Acme'])
        ->and(JobRun::query()->where('job', 'post_salary')->whereDate('run_for_date', '2026-10-30')->value('counts'))
        ->toMatchArray(['posted' => 0, 'already_logged' => 1]);
});

it('does not repost a salary that was deleted', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    autoSalary();
    runAutomationOn('2026-10-30');
    $credit = Transaction::query()->sole();

    $this->deleteJson("/api/v1/transactions/{$credit->id}")->assertNoContent();
    runAutomationOn('2026-10-31');

    expect(Transaction::query()->count())->toBe(0)
        ->and(JobRun::query()->where('job', 'post_salary')->latest('id')->value('counts'))->toMatchArray(['opted_out' => 1]);
});

it('does not re-settle an auto-debit that was reopened, but it can still be paid by hand', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    $this->postJson('/api/v1/recurring-payments', [
        'name' => 'SIP', 'amount' => 500_000, 'frequency' => 'monthly', 'day' => 10, 'auto_debit' => true,
        'active_from' => '2026-10-01', 'account_id' => $this->account->id,
    ])->assertCreated();
    runAutomationOn('2026-11-10');
    $sip = Due::query()->where('period', '2026-11')->sole();
    expect($sip->status->value)->toBe('paid');

    $this->postJson("/api/v1/dues/{$sip->id}/reopen")->assertOk();
    runAutomationOn('2026-11-11');
    expect($sip->refresh()->status->value)->toBe('upcoming');

    $this->postJson("/api/v1/dues/{$sip->id}/pay", ['amount' => 400_000])->assertOk();
    expect(ledgerLines())->toBe(['2026-11-11 debit 400000 SIP']);
});

it('leaves an auto-debit open when its account is archived', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    $this->postJson('/api/v1/recurring-payments', [
        'name' => 'SIP', 'amount' => 500_000, 'frequency' => 'monthly', 'day' => 10, 'auto_debit' => true,
        'active_from' => '2026-10-01', 'account_id' => $this->account->id,
    ])->assertCreated();
    runAutomationOn('2026-10-21');
    $this->account->forceFill(['archived_at' => now()])->save();

    runAutomationOn('2026-11-10');

    expect(Transaction::query()->count())->toBe(0)
        ->and(JobRun::query()->where('job', 'settle_auto_debits')->latest('id')->value('counts'))->toMatchArray(['skipped_no_account' => 1]);
});

it('confirms automatic entries, taking the actual amount of a variable salary', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    autoSalary(['is_variable' => true]);
    $this->postJson('/api/v1/loans', [
        'name' => 'Car loan', 'type' => 'car', 'principal' => 100_000_000, 'interest_rate' => 12, 'tenure_months' => 12,
        'first_emi_date' => '2026-08-31', 'account_id' => $this->account->id,
    ])->assertCreated();
    runAutomationOn('2026-10-31');
    $salary = Transaction::query()->where('direction', 'credit')->sole();
    $emi = Transaction::query()->where('direction', 'debit')->sole();

    $this->getJson('/api/v1/transactions?needs_confirmation=1')->assertJsonCount(2, 'data')->assertJsonPath('data.0.needs_confirmation', true);
    $this->getJson('/api/v1/dashboard')
        ->assertJsonPath('data.alerts.0.type', 'confirm_auto')
        ->assertJsonPath('data.alerts.0.message', 'Confirm the Salary - Acme amount for Oct 2026');

    $this->postJson("/api/v1/transactions/{$salary->id}/confirm", ['amount' => 11_840_000])
        ->assertOk()
        ->assertJsonPath('data.amount', 11_840_000)
        ->assertJsonPath('data.needs_confirmation', false);
    $this->postJson("/api/v1/transactions/{$emi->id}/confirm", ['amount' => 1])->assertStatus(409);
    $this->postJson("/api/v1/transactions/{$emi->id}/confirm")->assertOk();

    $this->getJson('/api/v1/transactions?needs_confirmation=1')->assertJsonCount(0, 'data');
    $this->getJson('/api/v1/dashboard')->assertJsonPath('data.income.credited', 11_840_000);
});

it('only confirms automatic entries', function () {
    test()->travelTo(CarbonImmutable::parse('2026-11-03 10:00:00', 'Asia/Kolkata'));
    $id = $this->postJson('/api/v1/transactions', [
        'account_id' => $this->account->id, 'direction' => 'debit', 'amount' => 100, 'occurred_on' => '2026-11-03',
    ])->json('data.id');

    $this->postJson("/api/v1/transactions/{$id}/confirm")->assertStatus(409);
});

it('shows what automation did', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    autoSalary();
    runAutomationOn('2026-10-30');

    $this->getJson('/api/v1/automation/runs')
        ->assertOk()
        ->assertJsonCount(3, 'data')
        ->assertJsonPath('data.0.job', 'settle_auto_debits')
        ->assertJsonPath('data.1.job', 'post_salary')
        ->assertJsonPath('data.1.run_for_date', '2026-10-30')
        ->assertJsonPath('data.1.counts.posted', 1);
});


it('does the day’s automation on the first request, without waiting for cron', function () {
    test()->travelTo(CarbonImmutable::parse('2026-10-20 10:00:00', 'Asia/Kolkata'));
    autoSalary();
    $this->postJson('/api/v1/loans', [
        'name' => 'Car loan', 'type' => 'car', 'principal' => 100_000_000, 'interest_rate' => 12, 'tenure_months' => 12,
        'first_emi_date' => '2026-08-31', 'account_id' => $this->account->id,
    ])->assertCreated();

    // Payday (30 Oct) and the EMI date (31 Oct) pass with no scheduler running at all.
    test()->travelTo(CarbonImmutable::parse('2026-10-31 08:00:00', 'Asia/Kolkata'));
    $data = $this->getJson('/api/v1/dashboard')->assertOk()->json('data');

    expect(ledgerLines())->toBe(['2026-10-30 credit 12000000 Salary - Acme', '2026-10-31 debit 8884900 Car loan EMI'])
        ->and($data['income']['credited'])->toBe(12_000_000)
        ->and($data['reserved_for_dues']['paid'])->toBe(8_884_900)
        ->and(collect($data['alerts'])->pluck('type')->unique()->values()->all())->toBe(['confirm_auto']);
});

it('runs the catch-up once a day, however many requests come in', function () {
    test()->travelTo(CarbonImmutable::parse('2026-11-03 10:00:00', 'Asia/Kolkata'));

    foreach (range(1, 5) as $i) {
        $this->getJson('/api/v1/dashboard')->assertOk();
    }
    expect(JobRun::query()->count())->toBe(3);

    test()->travelTo(CarbonImmutable::parse('2026-11-04 00:05:00', 'Asia/Kolkata')); // just past midnight, local time
    $this->getJson('/api/v1/accounts')->assertOk();
    expect(JobRun::query()->whereDate('run_for_date', '2026-11-04')->count())->toBe(3);
});

it('skips the catch-up when the scheduler already ran today', function () {
    test()->travelTo(CarbonImmutable::parse('2026-11-03 10:00:00', 'Asia/Kolkata'));
    $this->artisan('automation:run')->assertSuccessful();

    $this->getJson('/api/v1/dashboard')->assertOk();

    expect(JobRun::query()->count())->toBe(3);
});
