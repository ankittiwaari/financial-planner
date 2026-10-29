<?php

namespace App\Models;

use App\Enums\AccountType;
use App\Enums\Direction;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * @property int $opening_balance paise
 * @property int|null $credit_sum loaded by withBalance()
 * @property int|null $debit_sum loaded by withBalance()
 */
class Account extends Model
{
    /** @use HasFactory<\Database\Factories\AccountFactory> */
    use HasFactory;

    protected $fillable = ['name', 'type', 'opening_balance', 'is_primary'];

    protected function casts(): array
    {
        return [
            'type' => AccountType::class,
            'opening_balance' => 'integer',
            'is_primary' => 'boolean',
            'archived_at' => 'datetime',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function transactions(): HasMany
    {
        return $this->hasMany(Transaction::class);
    }

    /** Adds credit_sum and debit_sum so balance() needs no extra queries. */
    public function scopeWithBalance(Builder $query): void
    {
        $query->withSum(['transactions as credit_sum' => fn ($q) => $q->where('direction', Direction::Credit)], 'amount')
            ->withSum(['transactions as debit_sum' => fn ($q) => $q->where('direction', Direction::Debit)], 'amount');
    }

    public function scopeActive(Builder $query): void
    {
        $query->whereNull('archived_at');
    }

    /** opening_balance + credits − debits, in paise. */
    public function balance(): int
    {
        if (! array_key_exists('credit_sum', $this->attributes)) {
            $this->loadSum(['transactions as credit_sum' => fn ($q) => $q->where('direction', Direction::Credit)], 'amount')
                ->loadSum(['transactions as debit_sum' => fn ($q) => $q->where('direction', Direction::Debit)], 'amount');
        }

        return $this->opening_balance + (int) $this->credit_sum - (int) $this->debit_sum;
    }

    public function isArchived(): bool
    {
        return $this->archived_at !== null;
    }
}
