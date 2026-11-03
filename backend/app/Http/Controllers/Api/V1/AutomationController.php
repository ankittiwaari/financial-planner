<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Models\JobRun;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class AutomationController extends Controller
{
    /** What automation did recently, newest first: one row per job per day. */
    public function runs(Request $request): JsonResponse
    {
        $limit = $request->validate(['limit' => ['sometimes', 'integer', 'between:1,200']])['limit'] ?? 50;

        $runs = JobRun::query()
            ->where('user_id', $request->user()->id)
            ->latest('id')
            ->limit($limit)
            ->get()
            ->map(fn (JobRun $run) => [
                'id' => $run->id,
                'job' => $run->job,
                'run_for_date' => $run->run_for_date->toDateString(),
                'status' => $run->status,
                'counts' => $run->counts,
                'error' => $run->error,
                'ran_at' => $run->created_at?->toIso8601String(),
            ]);

        return response()->json(['data' => $runs]);
    }
}
