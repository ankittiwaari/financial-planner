<?php

namespace App\Support;

use Carbon\CarbonImmutable;

/** Small helpers for month-based schedules. Periods are "YYYY-MM" strings, which sort correctly as text. */
final class Months
{
    /** $day of the month containing $date; day 31 in a 30-day month means the 30th. */
    public static function onDay(CarbonImmutable $date, int $day): CarbonImmutable
    {
        $first = $date->startOfMonth()->startOfDay();

        return $first->setDay(min(max($day, 1), $first->daysInMonth));
    }

    public static function period(CarbonImmutable $date): string
    {
        return $date->format('Y-m');
    }

    public static function startOf(string $period): CarbonImmutable
    {
        return CarbonImmutable::createFromFormat('!Y-m', $period);
    }

    /** Whole calendar months from $from's month to $to's month (negative if $to is earlier). */
    public static function between(CarbonImmutable $from, CarbonImmutable $to): int
    {
        return ($to->year - $from->year) * 12 + ($to->month - $from->month);
    }
}
