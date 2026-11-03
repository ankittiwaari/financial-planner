<?php

namespace App\Services;

use App\Enums\Direction;
use App\Enums\DueStatus;
use App\Enums\LendingStatus;
use App\Models\CardStatement;
use App\Models\Due;
use App\Models\IncomeSource;
use App\Models\Lending;
use App\Models\Transaction;
use App\Models\User;
use App\Support\Months;
use Carbon\CarbonImmutable;
use Illuminate\Support\Collection;

/**
 * The one number: how much can be spent this cycle.
 *
 *     purchasing power = income this cycle (credited, or expected if not yet in)
 *                      + other money received this cycle (manual income, repayments of money lent)
 *                      − dues this cycle (EMIs, card bills, recurring; paid or not) − overdue dues still unpaid
 *                      − spent so far (debits, excluding due payments, which the dues line already covers)
 *                      − lent out this cycle
 *
 * Money still owed to you is reported as a receivable and never counted. All amounts are paise.
 */
final class ProjectionService
{
    public function __construct(private readonly DuesGenerator $dues) {}

    /** @return array{start: CarbonImmutable, end: CarbonImmutable, mode: string, anchor: IncomeSource|null} */
    public function cycle(User $user, CarbonImmutable $today): array
    {
        $anchor = $user->cycle_mode === 'calendar' ? null : $this->anchorSource($user, $today);
        if ($anchor === null) {
            return ['start' => $today->startOfMonth(), 'end' => $today->endOfMonth()->startOfDay(), 'mode' => 'calendar', 'anchor' => null];
        }

        // Payday to the day before the next payday. Weekend shifts can put this month's payday after today.
        $thisMonth = $anchor->payDateFor(Months::period($today));
        $start = $thisMonth->lte($today) ? $thisMonth : $anchor->payDateFor(Months::period($today->subMonthNoOverflow()));
        $next = $anchor->payDateFor(Months::period($start->addMonthNoOverflow()));

        return ['start' => $start, 'end' => $next->subDay(), 'mode' => 'payday', 'anchor' => $anchor];
    }

    /** @return array<string, mixed> the dashboard payload */
    public function dashboard(User $user): array
    {
        // A plain date, so it compares like the date-only pay and due dates (which carry no timezone).
        $today = CarbonImmutable::parse($user->today()->toDateString());
        $this->dues->forUser($user);
        ['start' => $start, 'end' => $end, 'mode' => $mode, 'anchor' => $anchor] = $this->cycle($user, $today);
        $range = [$start->toDateString(), $end->toDateString()];

        $income = $this->income($user, $start, $end, $today);
        $other = (int) $user->transactions()
            ->where('direction', Direction::Credit)
            ->whereBetween('occurred_on', $range)
            ->where(fn ($q) => $q->whereNull('source_type')->orWhere('source_type', '!=', Transaction::SOURCE_INCOME))
            ->sum('amount');

        $cycleDues = $this->cycleDues($user, $start, $end);
        $reserved = $this->reserved($cycleDues, $start);

        $debits = $user->transactions()
            ->where('direction', Direction::Debit)
            ->whereBetween('occurred_on', $range)
            ->where(fn ($q) => $q->whereNull('source_type')->orWhere('source_type', '!=', Due::TRANSACTION_SOURCE))
            ->selectRaw("case when source_type = ? then 'lent' else 'spent' end as kind, sum(amount) as total", [Lending::TRANSACTION_SOURCE])
            ->groupBy('kind')
            ->pluck('total', 'kind');
        $spent = (int) ($debits['spent'] ?? 0);
        $lentOut = (int) ($debits['lent'] ?? 0);

        $incomeTotal = $income['total'] + $other;
        $power = $incomeTotal - $reserved['total'] - $spent - $lentOut;
        $daysLeft = (int) $today->diffInDays($end) + 1;

        return [
            'cycle' => [
                'start' => $range[0],
                'end' => $range[1],
                'mode' => $mode,
                'anchor_income_source_id' => $anchor?->id,
                'days_total' => (int) $start->diffInDays($end) + 1,
                'days_left' => $daysLeft,
            ],
            'income' => [
                'expected' => $income['expected'],
                'credited' => $income['credited'],
                'other' => $other,
                'total' => $incomeTotal,
                'sources' => $income['sources'],
            ],
            'reserved_for_dues' => $reserved,
            'spent_so_far' => $spent,
            'lent_out' => $lentOut,
            'purchasing_power' => $power,
            'per_day_left' => intdiv(max(0, $power), $daysLeft),
            'receivables' => $this->receivables($user, $today),
            'next_dues' => $cycleDues
                ->filter(fn (Due $d) => $d->isOpen() && $d->due_date->gte($today))
                ->merge($user->dues()->unpaid()->where('due_date', '>', $range[1])->orderBy('due_date')->limit(5)->get())
                ->sortBy([['due_date', 'asc'], ['id', 'asc']])
                ->take(5)
                ->values(),
            'alerts' => $this->alerts($user, $income['sources'], $cycleDues, $today),
        ];
    }

    /** The salary the cycle runs from: the largest active income source. */
    private function anchorSource(User $user, CarbonImmutable $today): ?IncomeSource
    {
        return $user->incomeSources()
            ->whereDate('active_from', '<=', $today->toDateString())
            ->where(fn ($q) => $q->whereNull('active_to')->orWhere('active_to', '>=', $today->toDateString()))
            ->orderByDesc('amount')
            ->orderBy('id')
            ->first();
    }

    /**
     * Each active source's pay dates inside the cycle, with what was credited for that period or else the
     * expected amount.
     *
     * @return array{expected: int, credited: int, total: int, sources: list<array<string, mixed>>}
     */
    private function income(User $user, CarbonImmutable $start, CarbonImmutable $end, CarbonImmutable $today): array
    {
        $rows = [];
        foreach ($user->incomeSources()->with('credits')->orderBy('name')->get() as $source) {
            // A pay date in the cycle can belong to the month before the cycle starts up to the one it ends in.
            for ($month = $start->startOfMonth()->subMonthNoOverflow(); $month->lte($end); $month = $month->addMonthNoOverflow()) {
                $period = Months::period($month);
                $payDate = $source->payDateFor($period);
                $active = $payDate->gte($source->active_from) && ($source->active_to === null || $payDate->lte($source->active_to));
                if (! $active || ! $payDate->betweenIncluded($start, $end)) {
                    continue;
                }
                $credit = $source->credits->firstWhere('period', $period);
                $rows[] = [
                    'income_source_id' => $source->id,
                    'name' => $source->name,
                    'period' => $period,
                    'pay_date' => $payDate->toDateString(),
                    'expected' => $source->amount,
                    'credited' => $credit?->amount,
                    'is_variable' => $source->is_variable,
                    'awaiting' => $credit === null && $payDate->lte($today),
                ];
            }
        }
        $expected = array_sum(array_column($rows, 'expected'));
        $credited = array_sum(array_map(fn ($r) => (int) $r['credited'], $rows));
        $total = array_sum(array_map(fn ($r) => $r['credited'] ?? $r['expected'], $rows));

        return ['expected' => $expected, 'credited' => $credited, 'total' => $total, 'sources' => $rows];
    }

    /**
     * Dues this cycle is responsible for: those dated in it (unless skipped), plus older ones still unpaid or paid
     * during it.
     *
     * @return Collection<int, Due>
     */
    private function cycleDues(User $user, CarbonImmutable $start, CarbonImmutable $end): Collection
    {
        [$from, $to] = [$start->toDateString(), $end->toDateString()];

        return $user->dues()
            ->with(['source' => fn ($morph) => $morph->morphWith([CardStatement::class => ['card']])])
            ->where('status', '!=', DueStatus::Skipped)
            ->where(fn ($q) => $q
                ->where(fn ($q) => $q->whereDate('due_date', '>=', $from)->whereDate('due_date', '<=', $to))
                ->orWhere(fn ($q) => $q->whereDate('due_date', '<', $from)->where(fn ($q) => $q
                    ->where('status', DueStatus::Upcoming)
                    ->orWhere(fn ($q) => $q->whereDate('paid_on', '>=', $from)->whereDate('paid_on', '<=', $to)))))
            ->orderBy('due_date')
            ->orderBy('id')
            ->get();
    }

    /** @return array{total: int, emis: int, cards: int, recurring: int, overdue: int, paid: int, open: int} */
    private function reserved(Collection $dues, CarbonImmutable $start): array
    {
        // A paid due costs what was actually paid; an open one its amount.
        $cost = fn (Due $d) => $d->isOpen() ? $d->amount : (int) $d->paid_amount;
        $bySource = fn (string $type) => (int) $dues->where('source_type', $type)->sum($cost);

        return [
            'total' => (int) $dues->sum($cost),
            'emis' => $bySource(Due::SOURCE_LOAN),
            'cards' => $bySource(Due::SOURCE_CARD),
            'recurring' => $bySource(Due::SOURCE_RECURRING),
            // Carried in from before the cycle and still unpaid.
            'overdue' => (int) $dues->filter(fn (Due $d) => $d->isOpen() && $d->due_date->lt($start))->sum('amount'),
            'paid' => (int) $dues->reject(fn (Due $d) => $d->isOpen())->sum($cost),
            'open' => (int) $dues->filter(fn (Due $d) => $d->isOpen())->sum('amount'),
        ];
    }

    /** @return array{total_lent_outstanding: int, people: int, overdue: int} */
    private function receivables(User $user, CarbonImmutable $today): array
    {
        $owing = $user->lendings()->outstanding()->withSum('repayments as repaid_sum', 'amount')->get()
            ->filter(fn (Lending $l) => $l->outstanding() > 0);

        return [
            'total_lent_outstanding' => (int) $owing->sum(fn (Lending $l) => $l->outstanding()),
            'people' => $owing->map(fn (Lending $l) => LendingBook::personKey($l->person_name))->unique()->count(),
            'overdue' => (int) $owing->filter(fn (Lending $l) => $l->isOverdue($today))->sum(fn (Lending $l) => $l->outstanding()),
        ];
    }

    /**
     * Things to act on: salary not logged, card bills still estimated after the statement date, overdue dues.
     *
     * @param  list<array<string, mixed>>  $sources
     * @return list<array{type: string, message: string}>
     */
    private function alerts(User $user, array $sources, Collection $dues, CarbonImmutable $today): array
    {
        $alerts = [];
        foreach ($sources as $row) {
            if ($row['awaiting']) {
                $alerts[] = [
                    'type' => 'confirm_income',
                    'message' => "Log {$row['name']} for ".Months::startOf($row['period'])->format('M Y')
                        .($row['is_variable'] ? ' and confirm the amount' : '')
                        .' (payday was '.CarbonImmutable::parse($row['pay_date'])->format('j M').')',
                ];
            }
        }
        foreach ($dues as $due) {
            $statement = $due->source;
            if ($due->isOpen() && $statement instanceof CardStatement && $statement->isEstimate() && $statement->statement_date->lte($today)) {
                $alerts[] = [
                    'type' => 'enter_card_bill',
                    'message' => "Enter the {$statement->card->label()} bill (statement generated {$statement->statement_date->format('j M')})",
                ];
            }
        }
        $unconfirmed = $user->transactions()->where('is_auto', true)->whereNull('confirmed_at')->get();
        foreach ($unconfirmed as $transaction) {
            $variable = $transaction->source_type === Transaction::SOURCE_INCOME
                && IncomeSource::query()->whereKey($transaction->source_id)->value('is_variable');
            $alerts[] = [
                'type' => 'confirm_auto',
                'message' => $variable
                    ? "Confirm the {$transaction->description} amount for ".Months::startOf($transaction->period)->format('M Y')
                    : "Check {$transaction->description}, recorded automatically on ".CarbonImmutable::parse($transaction->occurred_on)->format('j M'),
                'transaction_id' => $transaction->id,
            ];
        }
        $overdue = $user->dues()->unpaid()->where('due_date', '<', $today->toDateString())->count();
        if ($overdue > 0) {
            $alerts[] = ['type' => 'overdue_dues', 'message' => $overdue === 1 ? '1 due is overdue' : "{$overdue} dues are overdue"];
        }
        $lateLendings = $user->lendings()->whereIn('status', [LendingStatus::Open, LendingStatus::PartiallyRepaid])
            ->whereNotNull('expected_by')->where('expected_by', '<', $today->toDateString())->count();
        if ($lateLendings > 0) {
            $alerts[] = ['type' => 'overdue_lendings', 'message' => $lateLendings === 1 ? '1 loan to a friend is past its expected date' : "{$lateLendings} loans to friends are past their expected date"];
        }

        return $alerts;
    }
}
