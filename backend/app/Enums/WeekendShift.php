<?php

namespace App\Enums;

enum WeekendShift: string
{
    case Before = 'before';
    case After = 'after';
    case None = 'none';
}
