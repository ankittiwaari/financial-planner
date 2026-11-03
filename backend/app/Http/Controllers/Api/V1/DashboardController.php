<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Resources\DueResource;
use App\Services\ProjectionService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class DashboardController extends Controller
{
    /** Purchasing power for the current cycle, with its breakdown, the next dues, receivables and alerts. */
    public function show(Request $request, ProjectionService $projection): JsonResponse
    {
        $dashboard = $projection->dashboard($request->user());
        $dashboard['next_dues'] = DueResource::collection($dashboard['next_dues'])->resolve($request);

        return response()->json(['data' => $dashboard]);
    }
}
