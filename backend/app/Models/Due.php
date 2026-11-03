<?php

namespace App\Models;

use App\Enums\DueStatus;
use Carbon\CarbonImmutable;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\MorphTo;

/**
 * One payment owed on a date: an EMI, a card bill or a recurring payment. Generated from its source, at most one
 * per source per period, so regenerating never duplicates.
 *
 * @property CarbonImmutable $due_date
 * @property CarbonImmutable|null $paid_on
 */
class Due extends Model
{
    public const SOURCE_LOAN = 'loan';

    public const SOURCE_CARD = 'card_statement';

    public const SOURCE_RECURRING = 'recurring_payment';

    /** source_type of the transaction created when a due is paid. */
    public const TRANSACTION_SOURCE = 'due';

    protected $fillable = [
        'source_type', 'source_id', 'period', 'title', 'amount', 'due_date', 'status', 'paid_on', 'paid_amount',
        'transaction_id', 'auto_generated',
    ];

    protected $attributes = ['status' => 'upcoming', 'auto_generated' => true];

    protected function casts(): array
    {
        return [
            'source_id' => 'integer',
            'amount' => 'integer',
            'due_date' => 'immutable_date',
            'status' => DueStatus::class,
            'paid_on' => 'immutable_date',
            'paid_amount' => 'integer',
            'auto_generated' => 'boolean',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    /** The loan, card statement or recurring payment. Null once that source is deleted; paid dues stay as history. */
    public function source(): MorphTo
    {
        return $this->morphTo();
    }

    public function transaction(): BelongsTo
    {
        return $this->belongsTo(Transaction::class);
    }

    public function scopeUnpaid(Builder $query): void
    {
        $query->where('status', DueStatus::Upcoming);
    }

    public function isOpen(): bool
    {
        return $this->status === DueStatus::Upcoming;
    }

    /** upcoming, overdue, paid or skipped. */
    public function displayStatus(CarbonImmutable $today): string
    {
        return $this->isOpen() && $this->due_date->lt($today) ? 'overdue' : $this->status->value;
    }

    /** The account the payment comes out of by default. */
    public function payFromAccountId(): ?int
    {
        return match (true) {
            $this->source instanceof Loan, $this->source instanceof RecurringPayment => $this->source->account_id,
            $this->source instanceof CardStatement => $this->source->card?->pay_from_account_id,
            default => null,
        };
    }
}
