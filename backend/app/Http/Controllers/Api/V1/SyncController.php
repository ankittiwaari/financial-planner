<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Resources\UserResource;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;
use Illuminate\Http\Resources\Json\ResourceCollection;
use Illuminate\Support\Facades\DB;

/**
 * Everything the app shows, in one round trip, for its offline cache. Each part is exactly what its own endpoint
 * returns (the same controllers answer), so the app parses them with the same code.
 *
 * A full snapshot rather than changes since a timestamp: at personal scale it's small, and it needs no tracking of
 * deletions. Transactions are the newest page, with its cursor for loading more online.
 */
class SyncController extends Controller
{
    public function show(Request $request): JsonResponse
    {
        // Dues are generated as part of the snapshot, so take it in one transaction for a consistent picture.
        $snapshot = DB::transaction(fn () => [
            'server_time' => now()->toIso8601String(),
            'user' => UserResource::make($request->user())->resolve($request),
            'accounts' => $this->data($request, AccountController::class, 'index'),
            'categories' => $this->data($request, CategoryController::class, 'index'),
            'income_sources' => $this->data($request, IncomeSourceController::class, 'index'),
            'transactions' => $this->page($request, TransactionController::class),
            'loans' => $this->data($request, LoanController::class, 'index'),
            'credit_cards' => $this->data($request, CreditCardController::class, 'index'),
            'recurring_payments' => $this->data($request, RecurringPaymentController::class, 'index'),
            'dues' => $this->data($request, DueController::class, 'index'),
            'lendings' => $this->data($request, LendingController::class, 'index'),
            'lending_summary' => $this->data($request, LendingController::class, 'summary'),
            'reminder_settings' => $this->data($request, ReminderSettingsController::class, 'show'),
            'dashboard' => $this->data($request, DashboardController::class, 'show'),
        ]);

        return response()->json(['data' => $snapshot]);
    }

    /** The "data" member of what $controller@$action would respond with to this request. */
    private function data(Request $request, string $controller, string $action): mixed
    {
        return $this->respond($request, $controller, $action)['data'];
    }

    /** @return array{data: mixed, meta: array{next_cursor: string|null}} */
    private function page(Request $request, string $controller): array
    {
        $body = $this->respond($request, $controller, 'index');

        return ['data' => $body['data'], 'meta' => ['next_cursor' => $body['meta']['next_cursor'] ?? null]];
    }

    /** @return array<string, mixed> */
    private function respond(Request $request, string $controller, string $action): array
    {
        $result = app()->call([app($controller), $action], ['request' => $request]);
        $response = $result instanceof JsonResource || $result instanceof ResourceCollection
            ? $result->response($request)
            : $result;

        return $response->getData(true);
    }
}
