<?php

namespace App\Services;

use App\Enums\Direction;
use App\Enums\LendingStatus;
use App\Models\Lending;
use App\Models\LendingRepayment;
use App\Models\Transaction;
use App\Models\User;
use App\Support\DefaultCategories;
use Carbon\CarbonImmutable;
use Illuminate\Support\Collection;
use Illuminate\Support\Facades\DB;
use Illuminate\Validation\ValidationException;

/**
 * Lending and repayments, kept in step with the ledger: money lent from an account is a debit, money repaid into
 * one is a credit (Repayment received). Either side may skip the account, for money that isn't tracked here.
 */
final class LendingBook
{
    /** @param array<string, mixed> $data validated LendingRequest payload */
    public function lend(User $user, array $data): Lending
    {
        return DB::transaction(function () use ($user, $data) {
            $lending = $user->lendings()->create($data);
            $this->syncLendTransaction($lending);

            return $lending;
        });
    }

    /** @param array<string, mixed> $data validated LendingRequest payload */
    public function update(Lending $lending, array $data): Lending
    {
        return DB::transaction(function () use ($lending, $data) {
            $repaid = (int) $lending->repayments()->sum('amount');
            if ($data['amount'] < $repaid) {
                throw ValidationException::withMessages([
                    'amount' => 'The amount can’t be less than the '.self::rupees($repaid).' already repaid.',
                ]);
            }
            $lending->update($data);
            $this->syncLendTransaction($lending);
            $lending->syncStatus();

            return $lending;
        });
    }

    /** Removes the lending with its repayments and every transaction they booked, as if it never happened. */
    public function delete(Lending $lending): void
    {
        DB::transaction(function () use ($lending) {
            $transactionIds = $lending->repayments()->pluck('transaction_id')->push($lending->transaction_id)->filter();
            $lending->delete();
            Transaction::query()->whereKey($transactionIds)->delete();
        });
    }

    public function repay(User $user, Lending $lending, int $amount, CarbonImmutable $receivedOn, ?int $accountId): LendingRepayment
    {
        return DB::transaction(function () use ($user, $lending, $amount, $receivedOn, $accountId) {
            $lending = Lending::query()->lockForUpdate()->findOrFail($lending->id);
            $owed = $lending->amount - (int) $lending->repayments()->sum('amount');
            if ($owed <= 0) {
                abort(409, "{$lending->person_name} has already repaid this in full.");
            }
            if ($amount > $owed) {
                throw ValidationException::withMessages([
                    'amount' => 'Only '.self::rupees($owed).' is still owed on this.',
                ]);
            }

            $repayment = $lending->repayments()->create([
                'account_id' => $accountId,
                'amount' => $amount,
                'received_on' => $receivedOn->toDateString(),
            ]);
            if ($accountId !== null) {
                $transaction = $user->transactions()->create([
                    'account_id' => $accountId,
                    'category_id' => DefaultCategories::incomeFor($user, DefaultCategories::REPAYMENT)->id,
                    'direction' => Direction::Credit,
                    'amount' => $amount,
                    'occurred_on' => $receivedOn->toDateString(),
                    'description' => "Repayment from {$lending->person_name}",
                    'source_type' => LendingRepayment::TRANSACTION_SOURCE,
                    'source_id' => $repayment->id,
                ]);
                $repayment->forceFill(['transaction_id' => $transaction->id])->save();
            }
            // Money coming back on a written-off loan brings it back to life.
            if ($lending->status === LendingStatus::WrittenOff) {
                $lending->status = LendingStatus::Open;
            }
            $lending->syncStatus();

            return $repayment;
        });
    }

    /** Undoes a repayment, deleting its transaction. */
    public function deleteRepayment(LendingRepayment $repayment): Lending
    {
        return DB::transaction(function () use ($repayment) {
            $lending = $repayment->lending;
            $transactionId = $repayment->transaction_id;
            $repayment->delete();
            Transaction::query()->whereKey($transactionId)->delete();
            $lending->syncStatus();

            return $lending;
        });
    }

    public function writeOff(Lending $lending, CarbonImmutable $today): Lending
    {
        abort_unless($lending->status->isOutstanding(), 409, "Nothing is owed on this, so there's nothing to write off.");
        $lending->forceFill(['status' => LendingStatus::WrittenOff, 'closed_on' => $today])->save();

        return $lending;
    }

    /** Undoes a write-off. */
    public function reopen(Lending $lending): Lending
    {
        abort_unless($lending->status === LendingStatus::WrittenOff, 409, 'Only a written-off loan can be reopened.');
        $lending->status = LendingStatus::Open;
        $lending->syncStatus();

        return $lending;
    }

    /**
     * Balances per person, grouping lendings by name (case and spacing ignored). People who still owe come first,
     * largest first; then everyone else by name.
     *
     * @return array{total_outstanding: int, overdue_total: int, people_owing: int, people: list<array<string, mixed>>}
     */
    public function summary(User $user): array
    {
        $today = $user->today();
        $lendings = $user->lendings()->withSum('repayments as repaid_sum', 'amount')->orderByDesc('lent_on')->orderByDesc('id')->get();

        $people = $lendings
            ->groupBy(fn (Lending $l) => self::personKey($l->person_name))
            ->map(function (Collection $group) use ($today) {
                /** @var Lending $latest */
                $latest = $group->first();
                $writtenOff = $group->where('status', LendingStatus::WrittenOff)->sum(fn (Lending $l) => max(0, $l->amount - $l->repaid()));
                $owing = $group->filter(fn (Lending $l) => $l->outstanding() > 0);

                return [
                    'name' => $latest->person_name,
                    'contact' => $group->firstWhere('contact', '!=', null)?->contact,
                    'lent' => (int) $group->sum('amount'),
                    'repaid' => (int) $group->sum(fn (Lending $l) => $l->repaid()),
                    'written_off' => (int) $writtenOff,
                    'outstanding' => (int) $owing->sum(fn (Lending $l) => $l->outstanding()),
                    'open_count' => $owing->count(),
                    'overdue' => $owing->contains(fn (Lending $l) => $l->isOverdue($today)),
                    'next_expected_by' => $owing->pluck('expected_by')->filter()->min()?->toDateString(),
                    'last_lent_on' => $latest->lent_on->toDateString(),
                    'lending_ids' => $group->pluck('id')->values()->all(),
                ];
            })
            ->sortBy([['outstanding', 'desc'], fn ($a, $b) => strcasecmp($a['name'], $b['name'])])
            ->values();

        return [
            'total_outstanding' => (int) $people->sum('outstanding'),
            'overdue_total' => (int) $lendings->filter(fn (Lending $l) => $l->isOverdue($today))->sum(fn (Lending $l) => $l->outstanding()),
            'people_owing' => $people->where('outstanding', '>', 0)->count(),
            'people' => $people->all(),
        ];
    }

    /** "  Ravi  Kumar" and "ravi kumar" are the same person. */
    public static function personKey(string $name): string
    {
        return mb_strtolower(preg_replace('/\s+/', ' ', trim($name)));
    }

    /** Books, moves or removes the debit for the money leaving an account, to match the lending. */
    private function syncLendTransaction(Lending $lending): void
    {
        $transaction = $lending->transaction;
        if ($lending->account_id === null) {
            if ($transaction) {
                $lending->forceFill(['transaction_id' => null])->save();
                $transaction->delete();
            }

            return;
        }

        $fields = [
            'account_id' => $lending->account_id,
            'direction' => Direction::Debit,
            'amount' => $lending->amount,
            'occurred_on' => $lending->lent_on->toDateString(),
            'description' => "Lent to {$lending->person_name}",
        ];
        if ($transaction) {
            $transaction->update($fields);
        } else {
            // No category: lending moves money out but isn't spending.
            $transaction = $lending->user->transactions()->create([
                ...$fields,
                'source_type' => Lending::TRANSACTION_SOURCE,
                'source_id' => $lending->id,
            ]);
            $lending->forceFill(['transaction_id' => $transaction->id])->save();
        }
    }

    private static function rupees(int $paise): string
    {
        return '₹'.number_format($paise / 100, $paise % 100 === 0 ? 0 : 2);
    }
}
