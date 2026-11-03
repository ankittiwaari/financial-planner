<?php

namespace App\Models;

use App\Enums\Frequency;
use App\Support\Months;
use Carbon\CarbonImmutable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Rent, SIPs, insurance premiums, subscriptions: a fixed amount every month, quarter or year.
 *
 * @property CarbonImmutable $active_from
 * @property CarbonImmutable|null $active_to
 */
class RecurringPayment extends Model
{
    /** @use HasFactory<\Database\Factories\RecurringPaymentFactory> */
    use HasFactory;

    protected $fillable = [
        'account_id', 'category_id', 'name', 'amount', 'frequency', 'day', 'auto_debit', 'active_from', 'active_to',
    ];

    protected $attributes = ['auto_debit' => false];

    protected function casts(): array
    {
        return [
            'amount' => 'integer',
            'frequency' => Frequency::class,
            'day' => 'integer',
            'auto_debit' => 'boolean',
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

    public function category(): BelongsTo
    {
        return $this->belongsTo(Category::class);
    }

    public function dues(): HasMany
    {
        return $this->hasMany(Due::class, 'source_id')->where('source_type', Due::SOURCE_RECURRING);
    }

    /**
     * Payment dates within [$from, $to] and the active range. Quarterly and yearly payments fall in active_from's
     * month and every 3 or 12 months after.
     *
     * @return list<CarbonImmutable>
     */
    public function occurrencesBetween(CarbonImmutable $from, CarbonImmutable $to): array
    {
        $start = $from->max($this->active_from);
        $end = $this->active_to ? $to->min($this->active_to) : $to;
        $step = $this->frequency->months();
        $dates = [];
        for ($month = $start->startOfMonth(); $month->lte($end); $month = $month->addMonthNoOverflow()) {
            if (Months::between($this->active_from, $month) % $step !== 0) {
                continue;
            }
            $date = Months::onDay($month, $this->day);
            if ($date->betweenIncluded($start, $end)) {
                $dates[] = $date;
            }
        }

        return $dates;
    }

    /** The first payment on or after $today, looking up to a year ahead. */
    public function nextDate(CarbonImmutable $today): ?CarbonImmutable
    {
        return $this->occurrencesBetween($today, $today->addYear())[0] ?? null;
    }
}
