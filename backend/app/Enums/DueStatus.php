<?php

namespace App\Enums;

/** Stored statuses. "overdue" is not stored: the API reports an upcoming due dated before today as overdue. */
enum DueStatus: string
{
    case Upcoming = 'upcoming';
    case Paid = 'paid';
    case Skipped = 'skipped';
}
