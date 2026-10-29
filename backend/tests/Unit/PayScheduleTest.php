<?php

use App\Enums\PayRule;
use App\Enums\WeekendShift;
use App\Support\PaySchedule;

function payDate(PayRule $rule, ?int $day, WeekendShift $shift, int $year, int $month): string
{
    return PaySchedule::payDate($rule, $day, $shift, $year, $month)->toDateString();
}

it('uses the last weekday of the month for last_working_day', function () {
    expect(payDate(PayRule::LastWorkingDay, null, WeekendShift::None, 2026, 10))->toBe('2026-10-30') // 31st is a Saturday
        ->and(payDate(PayRule::LastWorkingDay, null, WeekendShift::After, 2026, 11))->toBe('2026-11-30') // shift ignored
        ->and(payDate(PayRule::LastWorkingDay, null, WeekendShift::Before, 2026, 5))->toBe('2026-05-29'); // 31st is a Sunday
});

it('moves a weekend last_day according to the shift', function () {
    expect(payDate(PayRule::LastDay, null, WeekendShift::Before, 2026, 10))->toBe('2026-10-30')
        ->and(payDate(PayRule::LastDay, null, WeekendShift::After, 2026, 10))->toBe('2026-11-02')
        ->and(payDate(PayRule::LastDay, null, WeekendShift::None, 2026, 10))->toBe('2026-10-31')
        ->and(payDate(PayRule::LastDay, null, WeekendShift::Before, 2026, 4))->toBe('2026-04-30');
});

it('clamps a fixed day to short months before shifting', function () {
    expect(payDate(PayRule::FixedDay, 31, WeekendShift::None, 2026, 4))->toBe('2026-04-30')
        ->and(payDate(PayRule::FixedDay, 31, WeekendShift::None, 2026, 2))->toBe('2026-02-28')
        ->and(payDate(PayRule::FixedDay, 31, WeekendShift::Before, 2026, 2))->toBe('2026-02-27') // 28th is a Saturday
        ->and(payDate(PayRule::FixedDay, 1, WeekendShift::After, 2026, 11))->toBe('2026-11-02') // 1st is a Sunday
        ->and(payDate(PayRule::FixedDay, 7, WeekendShift::Before, 2026, 10))->toBe('2026-10-07');
});
