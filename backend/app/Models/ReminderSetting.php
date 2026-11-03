<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/** When to be reminded about dues. One row per user, created with the defaults on first read. */
class ReminderSetting extends Model
{
    /** T−5, T−2 and on the day, at 9 in the morning, plus a daily nudge while anything is overdue. */
    public const DEFAULTS = ['enabled' => true, 'offsets_days' => [5, 2, 0], 'time_of_day' => '09:00', 'overdue_daily' => true];

    protected $fillable = ['enabled', 'offsets_days', 'time_of_day', 'overdue_daily'];

    protected function casts(): array
    {
        return [
            'enabled' => 'boolean',
            'offsets_days' => 'array',
            'overdue_daily' => 'boolean',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public static function for(User $user): self
    {
        return $user->reminderSetting()->firstOrCreate([], self::DEFAULTS);
    }

    /** @return array<string, mixed> */
    public function toApi(): array
    {
        return [
            'enabled' => $this->enabled,
            'offsets_days' => array_values($this->offsets_days),
            'time_of_day' => $this->time_of_day,
            'overdue_daily' => $this->overdue_daily,
        ];
    }
}
