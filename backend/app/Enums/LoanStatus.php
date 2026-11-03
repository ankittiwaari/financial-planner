<?php

namespace App\Enums;

enum LoanStatus: string
{
    case Active = 'active';
    case Closed = 'closed';
}
