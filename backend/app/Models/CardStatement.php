<?php

namespace App\Models;

use App\Enums\StatementStatus;
use Carbon\CarbonImmutable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasOne;

/**
 * One month's bill on a credit card. Starts as an estimate and becomes "entered" once the real bill is typed in.
 *
 * @property CarbonImmutable $statement_date
 * @property CarbonImmutable $due_date
 */
class CardStatement extends Model
{
    protected $fillable = ['period', 'statement_date', 'due_date', 'total_due', 'min_due', 'amount_paid', 'status'];

    protected $attributes = ['amount_paid' => 0];

    protected function casts(): array
    {
        return [
            'statement_date' => 'immutable_date',
            'due_date' => 'immutable_date',
            'total_due' => 'integer',
            'min_due' => 'integer',
            'amount_paid' => 'integer',
            'status' => StatementStatus::class,
        ];
    }

    public function card(): BelongsTo
    {
        return $this->belongsTo(CreditCard::class, 'credit_card_id');
    }

    public function due(): HasOne
    {
        return $this->hasOne(Due::class, 'source_id')->where('source_type', Due::SOURCE_CARD);
    }

    public function isEstimate(): bool
    {
        return $this->status === StatementStatus::Estimated;
    }

    /** Records a payment (negative to undo one) and moves the status to match. */
    public function addPayment(int $amount): void
    {
        $this->amount_paid = max(0, $this->amount_paid + $amount);
        $this->status = match (true) {
            $this->amount_paid === 0 && $this->isEstimate() => StatementStatus::Estimated,
            $this->amount_paid === 0 => StatementStatus::Entered,
            $this->amount_paid >= $this->total_due => StatementStatus::Paid,
            default => StatementStatus::PartiallyPaid,
        };
        $this->save();
    }
}
