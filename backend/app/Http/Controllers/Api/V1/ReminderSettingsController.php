<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Models\ReminderSetting;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class ReminderSettingsController extends Controller
{
    public function show(Request $request): JsonResponse
    {
        return response()->json(['data' => ReminderSetting::for($request->user())->toApi()]);
    }

    public function update(Request $request): JsonResponse
    {
        $data = $request->validate([
            'enabled' => ['required', 'boolean'],
            'offsets_days' => ['present', 'array', 'max:6'], // may be empty: overdue nudges only
            'offsets_days.*' => ['integer', 'between:0,30', 'distinct'],
            'time_of_day' => ['required', 'string', 'regex:/^([01]\d|2[0-3]):[0-5]\d$/'],
            'overdue_daily' => ['required', 'boolean'],
        ], [
            'time_of_day.regex' => 'The time must look like 09:00.',
            'offsets_days.*.distinct' => 'Each reminder day can only be picked once.',
        ]);
        // Furthest first, as they fire.
        $data['offsets_days'] = collect($data['offsets_days'])->map(fn ($d) => (int) $d)->sortDesc()->values()->all();

        $settings = ReminderSetting::for($request->user());
        $settings->update($data);

        return response()->json(['data' => $settings->toApi()]);
    }
}
