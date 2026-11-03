<?php

namespace App\Services;

use App\Enums\LoanStatus;
use App\Enums\StatementStatus;
use App\Models\CardStatement;
use App\Models\CreditCard;
use App\Models\Due;
use App\Models\Loan;
use App\Models\RecurringPayment;
use App\Models\User;
use App\Support\Months;
use Carbon\CarbonImmutable;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\UniqueConstraintViolationException;

/**
 * Materialises dues for the next WINDOW_DAYS from loans, cards and recurring payments. Idempotent: a due is keyed
 * by (source_type, source_id, period), open dues are brought up to date, and paid or skipped ones are never touched.
 */
final class DuesGenerator
{
    public const WINDOW_DAYS = 45;

    public function forUser(User $user): void
    {
        [$from, $to] = $this->window($user);
        $user->loans()->active()->each(fn (Loan $loan) => $this->forLoan($loan, $from, $to));
        $user->creditCards()->each(fn (CreditCard $card) => $this->forCard($card, $from, $to));
        $user->recurringPayments()->each(fn (RecurringPayment $payment) => $this->forRecurring($payment, $from, $to));
    }

    /** After a source is edited: drops its open dues from today on and rebuilds them from the new details. */
    public function refresh(Loan|CreditCard|RecurringPayment $source): void
    {
        [$from, $to] = $this->window($source->user);
        $this->openDuesOf($source)->where('due_date', '>=', $from)->delete();

        match (true) {
            $source instanceof Loan => $source->status === LoanStatus::Active ? $this->forLoan($source, $from, $to) : null,
            $source instanceof CreditCard => $this->rebuildCard($source, $from, $to),
            $source instanceof RecurringPayment => $this->forRecurring($source, $from, $to),
        };
    }

    /** Before a source is deleted or closed: removes all its open dues. Paid and skipped ones stay as history. */
    public function discard(Loan|CreditCard|RecurringPayment $source): void
    {
        $this->openDuesOf($source)->delete();
    }

    public function forLoan(Loan $loan, CarbonImmutable $from, CarbonImmutable $to): void
    {
        $schedule = $loan->schedule();
        foreach ($schedule as $row) {
            if ($row['due_date']->betweenIncluded($from, $to)) {
                $this->upsert($loan->user_id, Due::SOURCE_LOAN, $loan->id, $row['due_date'], "{$loan->name} EMI", $row['emi']);
            }
        }

        // Past the last instalment with nothing left open: the loan is done.
        $last = end($schedule);
        if (($last === false || $last['due_date']->lt($from)) && ! $loan->dues()->unpaid()->exists()) {
            $loan->forceFill(['status' => LoanStatus::Closed, 'closed_on' => $last ? $last['due_date'] : $from])->save();
        }
    }

    /**
     * Ensures a statement, estimated if the real bill isn't in yet, for every bill due in the window, and its due.
     * Estimated statements are re-estimated each run, since entering a bill changes the estimate for later ones.
     */
    public function forCard(CreditCard $card, CarbonImmutable $from, CarbonImmutable $to): void
    {
        // A bill due in the window can come from a statement up to two months earlier.
        for ($month = $from->startOfMonth()->subMonthsNoOverflow(2); $month->lte($to); $month = $month->addMonthNoOverflow()) {
            $period = Months::period($month);
            $dueDate = $card->dueDateFor($period);
            if (! $dueDate->betweenIncluded($from, $to)) {
                continue;
            }
            $statement = $card->statements()->firstOrNew(['period' => $period]);
            if (! $statement->exists || $statement->isEstimate()) {
                $statement->fill([
                    'statement_date' => $card->statementDateFor($period),
                    'due_date' => $dueDate,
                    'total_due' => $card->estimateFor($period),
                    'status' => StatementStatus::Estimated,
                ])->save();
            }
            $this->syncStatement($statement->setRelation('card', $card));
        }
    }

    /** Creates or updates the due for one card statement. */
    public function syncStatement(CardStatement $statement): void
    {
        $card = $statement->card;
        $this->upsert($card->user_id, Due::SOURCE_CARD, $statement->id, $statement->due_date, "{$card->label()} bill",
            $statement->total_due, $statement->period);
    }

    public function forRecurring(RecurringPayment $payment, CarbonImmutable $from, CarbonImmutable $to): void
    {
        foreach ($payment->occurrencesBetween($from, $to) as $date) {
            $this->upsert($payment->user_id, Due::SOURCE_RECURRING, $payment->id, $date, $payment->name, $payment->amount);
        }
    }

    /** @return array{CarbonImmutable, CarbonImmutable} today and the end of the window, in the user's timezone */
    private function window(User $user): array
    {
        $today = $user->today();

        return [$today, $today->addDays(self::WINDOW_DAYS)];
    }

    private function rebuildCard(CreditCard $card, CarbonImmutable $from, CarbonImmutable $to): void
    {
        // Guesses dated on the old statement/due days would linger as stray statements.
        $card->statements()
            ->where('status', StatementStatus::Estimated)
            ->where('due_date', '>=', $from)
            ->whereDoesntHave('due', fn ($q) => $q->where('status', '!=', 'upcoming'))
            ->delete();
        $this->forCard($card, $from, $to);
    }

    private function openDuesOf(Loan|CreditCard|RecurringPayment $source): Builder
    {
        $query = $source instanceof CreditCard
            ? Due::query()->where('source_type', Due::SOURCE_CARD)->whereIn('source_id', $source->statements()->select('id'))
            : $source->dues()->getQuery();

        return $query->unpaid();
    }

    private function upsert(int $userId, string $type, int $sourceId, CarbonImmutable $dueDate, string $title, int $amount, ?string $period = null): void
    {
        $due = Due::query()->firstOrNew([
            'source_type' => $type,
            'source_id' => $sourceId,
            'period' => $period ?? Months::period($dueDate),
        ]);
        if ($due->exists && ! $due->isOpen()) {
            return;
        }

        try {
            $due->forceFill(['user_id' => $userId])->fill(['title' => $title, 'amount' => $amount, 'due_date' => $dueDate])->save();
        } catch (UniqueConstraintViolationException) {
            // A simultaneous run created it; that copy is just as good.
        }
    }
}
