<?php

namespace App\Models;

use App\Enums\PayRule;
use App\Enums\WeekendShift;
use App\Support\PaySchedule;
use Carbon\CarbonImmutable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * A recurring income such as a salary. Its credits are transactions with source_type = income_source.
 *
 * @property CarbonImmutable $active_from
 * @property CarbonImmutable|null $active_to
 */
class IncomeSource extends Model
{
    /** @use HasFactory<\Database\Factories\IncomeSourceFactory> */
    use HasFactory;

    protected $fillable = [
        'account_id', 'name', 'amount', 'is_variable', 'pay_rule', 'pay_day', 'weekend_shift',
        'auto_credit', 'active_from', 'active_to',
    ];

    protected function casts(): array
    {
        return [
            'amount' => 'integer',
            'is_variable' => 'boolean',
            'pay_rule' => PayRule::class,
            'pay_day' => 'integer',
            'weekend_shift' => WeekendShift::class,
            'auto_credit' => 'boolean',
            'active_from' => 'immutable_date',
            'active_to' => 'immutable_date',
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

    public function credits(): HasMany
    {
        return $this->hasMany(Transaction::class, 'source_id')->where('source_type', Transaction::SOURCE_INCOME);
    }

    /** The pay date for a "YYYY-MM" period. */
    public function payDateFor(string $period): CarbonImmutable
    {
        [$year, $month] = array_map('intval', explode('-', $period));

        return PaySchedule::payDate($this->pay_rule, $this->pay_day, $this->weekend_shift, $year, $month);
    }

    /** Next pay date on or after $today within the active range, or null once the source has ended. */
    public function nextPayDate(CarbonImmutable $today): ?CarbonImmutable
    {
        $from = $today->max($this->active_from);
        $month = $from->startOfMonth();
        // This month's pay date may already be behind us, so look a few months ahead.
        for ($i = 0; $i < 3; $i++, $month = $month->addMonthNoOverflow()) {
            $date = PaySchedule::payDate($this->pay_rule, $this->pay_day, $this->weekend_shift, $month->year, $month->month);
            if ($this->active_to && $date->gt($this->active_to)) {
                return null;
            }
            if ($date->gte($from)) {
                return $date;
            }
        }

        return null;
    }
}
