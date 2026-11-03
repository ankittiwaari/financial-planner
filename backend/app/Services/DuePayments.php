<?php

namespace App\Services;

use App\Enums\Direction;
use App\Enums\DueStatus;
use App\Models\Account;
use App\Models\AutomationOptOut;
use App\Models\CardStatement;
use App\Models\Category;
use App\Models\Due;
use App\Models\Loan;
use App\Models\RecurringPayment;
use App\Models\Transaction;
use App\Models\User;
use App\Support\DefaultCategories;
use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\DB;
use Illuminate\Validation\ValidationException;

/**
 * Paying, skipping and reopening dues. Paying books a debit transaction linked back to the due, so the money
 * leaves the account; reopening deletes that transaction again.
 */
final class DuePayments
{
    /** @param bool $auto booked by automation (an auto-debit settling), so it waits for confirmation */
    public function pay(User $user, Due $due, ?int $amount, ?CarbonImmutable $paidOn, ?int $accountId, bool $auto = false): Due
    {
        $amount ??= $due->amount;
        if ($amount < 1) {
            throw ValidationException::withMessages(['amount' => 'Enter the amount you paid.']);
        }
        $accountId ??= $due->payFromAccountId();
        $account = $accountId ? $user->accounts()->active()->find($accountId) : null;
        if (! $account instanceof Account) {
            throw ValidationException::withMessages(['account_id' => 'Choose one of your active accounts to pay from.']);
        }

        return DB::transaction(function () use ($user, $due, $amount, $paidOn, $account, $auto) {
            $due = Due::query()->lockForUpdate()->findOrFail($due->id);
            $this->ensureOpen($due);

            $transaction = $user->transactions()->create([
                'account_id' => $account->id,
                'category_id' => $this->categoryFor($user, $due)->id,
                'direction' => Direction::Debit,
                'amount' => $amount,
                'occurred_on' => ($paidOn ?? $user->today())->toDateString(),
                'description' => $due->title,
                'source_type' => Due::TRANSACTION_SOURCE,
                'source_id' => $due->id,
                'period' => $due->period,
                'is_auto' => $auto,
            ]);
            $due->update([
                'status' => DueStatus::Paid,
                'paid_on' => $transaction->occurred_on,
                'paid_amount' => $amount,
                'transaction_id' => $transaction->id,
            ]);
            if ($due->source instanceof CardStatement) {
                $due->source->addPayment($amount);
            }

            return $due;
        });
    }

    public function skip(Due $due): Due
    {
        $this->ensureOpen($due);
        $due->update(['status' => DueStatus::Skipped]);

        return $due;
    }

    /**
     * Undoes a payment or skip. The payment's transaction is deleted, so the money is back in the account. Undoing an
     * automatic settlement also stops automation from settling this due again.
     */
    public function reopen(Due $due): Due
    {
        return DB::transaction(function () use ($due) {
            $due = Due::query()->lockForUpdate()->findOrFail($due->id);
            if ($due->status === DueStatus::Paid) {
                if ($due->source instanceof CardStatement) {
                    $due->source->addPayment(-(int) $due->paid_amount);
                }
                $transactionId = $due->transaction_id;
                if (Transaction::query()->whereKey($transactionId)->value('is_auto')) {
                    AutomationOptOut::record($due->user_id, Due::TRANSACTION_SOURCE, $due->id, $due->period);
                }
                $due->update(['transaction_id' => null]);
                Transaction::query()->whereKey($transactionId)->delete();
            }
            $due->update(['status' => DueStatus::Upcoming, 'paid_on' => null, 'paid_amount' => null]);

            return $due;
        });
    }

    private function ensureOpen(Due $due): void
    {
        if (! $due->isOpen()) {
            abort(409, "{$due->title} is already marked {$due->status->value}.");
        }
    }

    private function categoryFor(User $user, Due $due): Category
    {
        return match (true) {
            $due->source instanceof Loan => DefaultCategories::expenseFor($user, DefaultCategories::LOAN_EMI),
            $due->source instanceof CardStatement => DefaultCategories::expenseFor($user, DefaultCategories::CARD_BILL),
            $due->source instanceof RecurringPayment && $due->source->category_id !== null => $due->source->category,
            default => DefaultCategories::expenseFor($user, DefaultCategories::BILLS),
        };
    }
}
