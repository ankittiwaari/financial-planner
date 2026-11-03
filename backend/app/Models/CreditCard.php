<?php

namespace App\Models;

use App\Enums\EstimateMode;
use App\Enums\StatementStatus;
use App\Support\Months;
use Carbon\CarbonImmutable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\Relations\HasOne;

/**
 * A credit card, identified by nickname and last 4 digits only. Each month's bill is a CardStatement, and each
 * statement produces one due.
 */
class CreditCard extends Model
{
    /** @use HasFactory<\Database\Factories\CreditCardFactory> */
    use HasFactory;

    protected $fillable = [
        'pay_from_account_id', 'nickname', 'last4', 'issuer', 'credit_limit', 'statement_day', 'due_day',
        'estimate_mode', 'fixed_estimate',
    ];

    protected $attributes = ['estimate_mode' => 'last_statement'];

    protected function casts(): array
    {
        return [
            'credit_limit' => 'integer',
            'statement_day' => 'integer',
            'due_day' => 'integer',
            'estimate_mode' => EstimateMode::class,
            'fixed_estimate' => 'integer',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function payFromAccount(): BelongsTo
    {
        return $this->belongsTo(Account::class, 'pay_from_account_id');
    }

    public function statements(): HasMany
    {
        return $this->hasMany(CardStatement::class);
    }

    public function latestStatement(): HasOne
    {
        return $this->hasOne(CardStatement::class)->ofMany('period', 'max');
    }

    public function statementDateFor(string $period): CarbonImmutable
    {
        return Months::onDay(Months::startOf($period), $this->statement_day);
    }

    /** The due day after the statement: same month if it comes later in the month, otherwise the next month. */
    public function dueDateFor(string $period): CarbonImmutable
    {
        $month = Months::startOf($period);
        if ($this->due_day <= $this->statement_day) {
            $month = $month->addMonthNoOverflow();
        }

        return Months::onDay($month, $this->due_day);
    }

    /** A guess at the bill for $period, from the statements entered before it. */
    public function estimateFor(string $period): int
    {
        if ($this->estimate_mode === EstimateMode::Fixed) {
            return (int) $this->fixed_estimate;
        }
        $previous = $this->statements()
            ->where('period', '<', $period)
            ->where('status', '!=', StatementStatus::Estimated)
            ->orderByDesc('period')
            ->limit($this->estimate_mode === EstimateMode::Average3 ? 3 : 1)
            ->pluck('total_due');

        return $previous->isEmpty() ? 0 : (int) round($previous->avg());
    }

    public function label(): string
    {
        return $this->last4 ? "{$this->nickname} ••{$this->last4}" : $this->nickname;
    }
}
