<?php

namespace App\Services;

use App\Enums\Direction;
use App\Models\AutomationOptOut;
use App\Models\Due;
use App\Models\IncomeSource;
use App\Models\JobRun;
use App\Models\Loan;
use App\Models\RecurringPayment;
use App\Models\Transaction;
use App\Models\User;
use App\Support\DefaultCategories;
use App\Support\Months;
use Carbon\CarbonImmutable;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Support\Facades\Cache;
use Throwable;

/**
 * The daily run, per user. Each job is idempotent and logged to job_runs:
 *
 * - generate_dues: dues for the next 45 days (and loans closing after their last EMI).
 * - post_salary: on payday, credit each auto_credit income source (variable ones at the expected amount).
 * - settle_auto_debits: on the due date, mark auto-debit EMIs and recurring payments paid.
 *
 * Automated is not silent: everything it books is is_auto and waits for confirmation, and anything the person
 * undoes is recorded in automation_opt_outs so it isn't redone. Card bills are never auto-settled.
 * Overdue status and the dues reserve aren't jobs: both are worked out whenever they're read.
 */
final class DailyAutomation
{
    /** Pay dates missed by up to this many days (the scheduler was down) are still posted. */
    public const CATCH_UP_DAYS = 7;

    public function __construct(private readonly DuesGenerator $dues, private readonly DuePayments $payments) {}

    /**
     * Runs today's jobs for this user unless they've already run today (on schedule or on an earlier request), so
     * numbers never wait for cron: a salary due today is in and today's auto-debits are settled before any request
     * is answered. A failed job isn't retried here on every request; the hourly schedule retries it.
     */
    public function catchUp(User $user): void
    {
        $today = $user->today()->toDateString();
        $ranToday = fn () => JobRun::query()->where('user_id', $user->id)->whereDate('run_for_date', $today)->exists();
        if ($ranToday()) {
            return;
        }
        // Parallel requests (the app fires several at once) would otherwise all run it. Whoever doesn't get the lock
        // goes ahead without waiting: everything they read is still correct, just not yet automated.
        Cache::lock("automation:user:{$user->id}", 120)->get(function () use ($user, $ranToday) {
            if (! $ranToday()) {
                $this->run($user);
            }
        });
    }

    /** @return array<string, array<string, int>|string> counts per job, or the error message of a failed job */
    public function run(User $user): array
    {
        // A plain date, so it compares like the date-only pay and due dates.
        $today = CarbonImmutable::parse($user->today()->toDateString());
        $jobs = [
            'generate_dues' => fn () => $this->generateDues($user),
            'post_salary' => fn () => $this->postSalary($user, $today),
            'settle_auto_debits' => fn () => $this->settleAutoDebits($user, $today),
        ];

        $results = [];
        foreach ($jobs as $job => $run) {
            try {
                $counts = $run();
                JobRun::query()->create(['user_id' => $user->id, 'job' => $job, 'run_for_date' => $today->toDateString(), 'status' => 'ok', 'counts' => $counts]);
                $results[$job] = $counts;
            } catch (Throwable $e) {
                // One job failing mustn't stop the others.
                report($e);
                JobRun::query()->create(['user_id' => $user->id, 'job' => $job, 'run_for_date' => $today->toDateString(), 'status' => 'failed', 'error' => $e->getMessage()]);
                $results[$job] = $e->getMessage();
            }
        }

        return $results;
    }

    /** @return array<string, int> */
    private function generateDues(User $user): array
    {
        $before = $user->dues()->count();
        $this->dues->forUser($user);

        return ['created' => $user->dues()->count() - $before];
    }

    /**
     * Credits each auto_credit source on its pay date. Only pay dates since the source was added are posted, so
     * adding a salary never back-fills months of history.
     *
     * @return array<string, int>
     */
    private function postSalary(User $user, CarbonImmutable $today): array
    {
        $counts = ['posted' => 0, 'already_logged' => 0, 'opted_out' => 0, 'skipped_archived_account' => 0];
        $sources = $user->incomeSources()->with('account')->where('auto_credit', true)->get();

        foreach ($sources as $source) {
            $addedOn = CarbonImmutable::parse($source->created_at->timezone($user->timezone ?: 'Asia/Kolkata')->toDateString());
            foreach ([$today->subMonthNoOverflow(), $today] as $month) {
                $period = Months::period($month);
                $payDate = $source->payDateFor($period);
                if ($payDate->gt($today) || $payDate->lt($today->subDays(self::CATCH_UP_DAYS)) || $payDate->lt($addedOn)
                    || $payDate->lt($source->active_from) || ($source->active_to && $payDate->gt($source->active_to))) {
                    continue;
                }
                $counts[$this->postCredit($user, $source, $period, $payDate)]++;
            }
        }

        return $counts;
    }

    private function postCredit(User $user, IncomeSource $source, string $period, CarbonImmutable $payDate): string
    {
        if ($source->credits()->where('period', $period)->exists()) {
            return 'already_logged';
        }
        if (AutomationOptOut::exists(Transaction::SOURCE_INCOME, $source->id, $period)) {
            return 'opted_out';
        }
        if ($source->account->isArchived()) {
            return 'skipped_archived_account';
        }

        try {
            $user->transactions()->create([
                'account_id' => $source->account_id,
                'category_id' => DefaultCategories::salaryFor($user)->id,
                'direction' => Direction::Credit,
                'amount' => $source->amount, // the expected amount; a variable salary is confirmed afterwards
                'occurred_on' => $payDate->toDateString(),
                'description' => $source->name,
                'source_type' => Transaction::SOURCE_INCOME,
                'source_id' => $source->id,
                'period' => $period,
                'is_auto' => true,
            ]);
        } catch (UniqueConstraintViolationException) {
            return 'already_logged'; // a simultaneous run or a manual log won
        }

        return 'posted';
    }

    /**
     * Settles open dues whose source is paid by the bank on its own (NACH EMIs, SIP mandates), dated on the due date.
     *
     * @return array<string, int>
     */
    private function settleAutoDebits(User $user, CarbonImmutable $today): array
    {
        $counts = ['settled' => 0, 'opted_out' => 0, 'skipped_no_account' => 0];
        $dues = $user->dues()->unpaid()
            ->whereIn('source_type', [Due::SOURCE_LOAN, Due::SOURCE_RECURRING])
            ->whereDate('due_date', '<=', $today->toDateString())
            ->with('source')
            ->orderBy('due_date')
            ->get()
            ->filter(fn (Due $d) => ($d->source instanceof Loan || $d->source instanceof RecurringPayment) && $d->source->auto_debit);

        foreach ($dues as $due) {
            if (AutomationOptOut::exists(Due::TRANSACTION_SOURCE, $due->id, $due->period)) {
                $counts['opted_out']++;

                continue;
            }
            if ($user->accounts()->active()->whereKey($due->payFromAccountId())->doesntExist()) {
                $counts['skipped_no_account']++; // left open: the person pays it by hand

                continue;
            }
            $this->payments->pay($user, $due, null, $due->due_date, null, auto: true);
            $counts['settled']++;
        }

        return $counts;
    }
}
