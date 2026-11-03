<?php

namespace App\Models;

use App\Enums\LendingStatus;
use Carbon\CarbonImmutable;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Money lent to one person on one date. Repayments bring it down; it is a receivable, never spendable money.
 *
 * @property CarbonImmutable $lent_on
 * @property CarbonImmutable|null $expected_by
 * @property CarbonImmutable|null $closed_on
 * @property int|null $repaid_sum loaded with withSum('repayments as repaid_sum', 'amount')
 */
class Lending extends Model
{
    /** @use HasFactory<\Database\Factories\LendingFactory> */
    use HasFactory;

    /** source_type of the debit booked when the money left an account. */
    public const TRANSACTION_SOURCE = 'lending';

    protected $fillable = ['account_id', 'person_name', 'contact', 'amount', 'lent_on', 'expected_by', 'notes'];

    protected $attributes = ['status' => 'open'];

    protected function casts(): array
    {
        return [
            'amount' => 'integer',
            'lent_on' => 'immutable_date',
            'expected_by' => 'immutable_date',
            'status' => LendingStatus::class,
            'closed_on' => 'immutable_date',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function account(): BelongsTo
    {
        return $this->belongsTo(Account::class);
    }

    public function transaction(): BelongsTo
    {
        return $this->belongsTo(Transaction::class);
    }

    public function repayments(): HasMany
    {
        return $this->hasMany(LendingRepayment::class);
    }

    public function scopeOutstanding(Builder $query): void
    {
        $query->whereIn('status', [LendingStatus::Open, LendingStatus::PartiallyRepaid]);
    }

    public function repaid(): int
    {
        return (int) ($this->repaid_sum ?? $this->repayments()->sum('amount'));
    }

    /** Still owed. Zero once settled or written off. */
    public function outstanding(): int
    {
        return $this->status->isOutstanding() ? max(0, $this->amount - $this->repaid()) : 0;
    }

    public function isOverdue(CarbonImmutable $today): bool
    {
        return $this->outstanding() > 0 && $this->expected_by !== null && $this->expected_by->lt($today);
    }

    /** Moves between open, partially repaid and settled to match the repayments. Leaves a write-off alone. */
    public function syncStatus(): void
    {
        if ($this->status === LendingStatus::WrittenOff) {
            return;
        }
        $repaid = (int) $this->repayments()->sum('amount');
        $this->status = match (true) {
            $repaid >= $this->amount => LendingStatus::Settled,
            $repaid > 0 => LendingStatus::PartiallyRepaid,
            default => LendingStatus::Open,
        };
        $this->closed_on = $this->status === LendingStatus::Settled
            ? $this->repayments()->max('received_on')
            : null;
        $this->save();
        unset($this->repaid_sum); // a value loaded by withSum() is stale now
    }
}
