<?php

namespace App\Enums;

enum LendingStatus: string
{
    case Open = 'open';
    case PartiallyRepaid = 'partially_repaid';
    case Settled = 'settled';
    /** Given up on. The unpaid part no longer counts as owed. */
    case WrittenOff = 'written_off';

    public function isOutstanding(): bool
    {
        return $this === self::Open || $this === self::PartiallyRepaid;
    }
}
