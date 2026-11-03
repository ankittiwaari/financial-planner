<?php

namespace App\Enums;

/** How a card bill is guessed before the real statement is entered. */
enum EstimateMode: string
{
    /** The previous entered statement's total. */
    case LastStatement = 'last_statement';
    /** The average of the last three entered statements. */
    case Average3 = 'avg_3';
    /** The card's fixed_estimate. */
    case Fixed = 'fixed';
}
