<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * Something automation did that the person undid (an auto-posted salary deleted, an auto-settled due reopened).
 * Automation checks these so it never redoes it.
 */
class AutomationOptOut extends Model
{
    public const UPDATED_AT = null;

    protected $fillable = ['user_id', 'source_type', 'source_id', 'period'];

    public static function record(int $userId, string $sourceType, int $sourceId, string $period): void
    {
        static::query()->firstOrCreate(
            ['source_type' => $sourceType, 'source_id' => $sourceId, 'period' => $period],
            ['user_id' => $userId],
        );
    }

    public static function exists(string $sourceType, int $sourceId, string $period): bool
    {
        return static::query()->where(['source_type' => $sourceType, 'source_id' => $sourceId, 'period' => $period])->exists();
    }
}
