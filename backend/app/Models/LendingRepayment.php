<?php

namespace App\Models;

use Carbon\CarbonImmutable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * Part (or all) of a lending paid back.
 *
 * @property CarbonImmutable $received_on
 */
class LendingRepayment extends Model
{
    /** source_type of the credit booked when the money came into an account. */
    public const TRANSACTION_SOURCE = 'lending_repayment';

    protected $fillable = ['account_id', 'amount', 'received_on'];

    protected function casts(): array
    {
        return [
            'amount' => 'integer',
            'received_on' => 'immutable_date',
        ];
    }

    public function lending(): BelongsTo
    {
        return $this->belongsTo(Lending::class);
    }

    public function transaction(): BelongsTo
    {
        return $this->belongsTo(Transaction::class);
    }
}
