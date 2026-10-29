<?php

namespace App\Support;

use App\Enums\PayRule;
use App\Enums\WeekendShift;
use Carbon\CarbonImmutable;

/**
 * Works out when income lands in a given month. Weekends only: public holidays aren't modelled yet.
 */
final class PaySchedule
{
    public static function payDate(PayRule $rule, ?int $payDay, WeekendShift $shift, int $year, int $month): CarbonImmutable
    {
        $first = CarbonImmutable::create($year, $month, 1)->startOfDay();
        $last = $first->endOfMonth()->startOfDay();

        return match ($rule) {
            // pay_day 31 in a 30-day month means the 30th.
            PayRule::FixedDay => self::shift($first->setDay(min(max($payDay ?? 1, 1), $last->day)), $shift),
            PayRule::LastDay => self::shift($last, $shift),
            PayRule::LastWorkingDay => self::shift($last, WeekendShift::Before),
        };
    }

    private static function shift(CarbonImmutable $date, WeekendShift $shift): CarbonImmutable
    {
        if (! $date->isWeekend() || $shift === WeekendShift::None) {
            return $date;
        }

        return $shift === WeekendShift::Before ? $date->previous(CarbonImmutable::FRIDAY) : $date->next(CarbonImmutable::MONDAY);
    }
}
