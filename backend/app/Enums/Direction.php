<?php

namespace App\Enums;

/** credit = money in, debit = money out. */
enum Direction: string
{
    case Credit = 'credit';
    case Debit = 'debit';

    /** The category kind a transaction in this direction must use. */
    public function categoryKind(): CategoryKind
    {
        return $this === self::Credit ? CategoryKind::Income : CategoryKind::Expense;
    }
}
