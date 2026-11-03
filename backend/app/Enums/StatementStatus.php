<?php

namespace App\Enums;

enum StatementStatus: string
{
    /** Generated ahead of the real bill; total_due is a guess. */
    case Estimated = 'estimated';
    case Entered = 'entered';
    case PartiallyPaid = 'partially_paid';
    case Paid = 'paid';
}
