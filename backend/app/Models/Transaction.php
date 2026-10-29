<?php

namespace App\Models;

use App\Enums\Direction;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * One movement of money in or out of an account. amount is positive paise; direction gives the sign.
 */
class Transaction extends Model
{
    /** @use HasFactory<\Database\Factories\TransactionFactory> */
    use HasFactory;

    /** source_type for credits logged against an income source. */
    public const SOURCE_INCOME = 'income_source';

    /** Mirrors the column default so freshly created rows serialise is_auto as false, not null. */
    protected $attributes = ['is_auto' => false];

    protected $fillable = [
        'account_id', 'category_id', 'direction', 'amount', 'occurred_on', 'description',
        'source_type', 'source_id', 'period', 'is_auto',
    ];

    protected function casts(): array
    {
        return [
            'direction' => Direction::class,
            'amount' => 'integer',
            // occurred_on stays a plain "Y-m-d" string (validated on input). A date cast would store
            // "Y-m-d 00:00:00" while cursor pagination compares against an ISO timestamp, repeating rows.
            'is_auto' => 'boolean',
        ];
    }

    public function account(): BelongsTo
    {
        return $this->belongsTo(Account::class);
    }

    public function category(): BelongsTo
    {
        return $this->belongsTo(Category::class);
    }
}
