<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/** One automation job's run for one user and day: what it did, or why it failed. */
class JobRun extends Model
{
    public const UPDATED_AT = null;

    protected $fillable = ['user_id', 'job', 'run_for_date', 'status', 'counts', 'error'];

    protected function casts(): array
    {
        return [
            'run_for_date' => 'immutable_date',
            'counts' => 'array',
        ];
    }
}
