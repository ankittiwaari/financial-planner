<?php

namespace App\Enums;

enum PayRule: string
{
    case FixedDay = 'fixed_day';
    case LastWorkingDay = 'last_working_day';
    case LastDay = 'last_day';
}
