<?php

namespace App\Http\Controllers\Api\V1;

use App\Enums\Direction;
use App\Http\Controllers\Controller;
use App\Http\Requests\TransactionRequest;
use App\Http\Resources\TransactionResource;
use App\Models\Transaction;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\AnonymousResourceCollection;
use Illuminate\Http\Response;
use Illuminate\Validation\Rule;

class TransactionController extends Controller
{
    /** Newest first, cursor-paginated: {data, meta: {next_cursor}}. */
    public function index(Request $request): AnonymousResourceCollection
    {
        $filters = $request->validate([
            'from' => ['sometimes', 'date_format:Y-m-d'],
            'to' => ['sometimes', 'date_format:Y-m-d'],
            'account_id' => ['sometimes', 'integer'],
            'category_id' => ['sometimes', 'integer'],
            'direction' => ['sometimes', Rule::enum(Direction::class)],
            'per_page' => ['sometimes', 'integer', 'between:1,100'],
        ]);

        $transactions = $request->user()->transactions()
            ->when($filters['from'] ?? null, fn ($q, $from) => $q->where('occurred_on', '>=', $from))
            ->when($filters['to'] ?? null, fn ($q, $to) => $q->where('occurred_on', '<=', $to))
            ->when($filters['account_id'] ?? null, fn ($q, $id) => $q->where('account_id', $id))
            ->when($filters['category_id'] ?? null, fn ($q, $id) => $q->where('category_id', $id))
            ->when($filters['direction'] ?? null, fn ($q, $direction) => $q->where('direction', $direction))
            ->orderByDesc('occurred_on')
            ->orderByDesc('id')
            ->cursorPaginate($filters['per_page'] ?? 50)
            ->withQueryString();

        return TransactionResource::collection($transactions);
    }

    public function summary(Request $request): JsonResponse
    {
        $range = $request->validate([
            'from' => ['required', 'date_format:Y-m-d'],
            'to' => ['required', 'date_format:Y-m-d', 'after_or_equal:from'],
        ]);

        $totals = $request->user()->transactions()
            ->whereBetween('occurred_on', [$range['from'], $range['to']])
            ->selectRaw('direction, SUM(amount) as total')
            ->groupBy('direction')
            ->pluck('total', 'direction');

        $credit = (int) ($totals[Direction::Credit->value] ?? 0);
        $debit = (int) ($totals[Direction::Debit->value] ?? 0);

        return response()->json(['data' => [
            'from' => $range['from'],
            'to' => $range['to'],
            'credit_total' => $credit,
            'debit_total' => $debit,
            'net' => $credit - $debit,
        ]]);
    }

    public function store(TransactionRequest $request): JsonResponse
    {
        $transaction = $request->user()->transactions()->create($request->payload());

        return TransactionResource::make($transaction)->response()->setStatusCode(201);
    }

    public function show(Transaction $transaction): TransactionResource
    {
        return TransactionResource::make($transaction);
    }

    public function update(TransactionRequest $request, Transaction $transaction): TransactionResource
    {
        $transaction->update($request->payload());

        return TransactionResource::make($transaction);
    }

    public function destroy(Transaction $transaction): Response
    {
        $transaction->delete();

        return response()->noContent();
    }
}
