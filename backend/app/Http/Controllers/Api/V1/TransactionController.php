<?php

namespace App\Http\Controllers\Api\V1;

use App\Enums\Direction;
use App\Http\Controllers\Controller;
use App\Http\Requests\TransactionRequest;
use App\Http\Resources\TransactionResource;
use App\Models\AutomationOptOut;
use App\Models\Due;
use App\Models\Lending;
use App\Models\LendingRepayment;
use App\Models\Transaction;
use App\Services\DuePayments;
use App\Services\LendingBook;
use Illuminate\Support\Facades\DB;
use Illuminate\Database\UniqueConstraintViolationException;
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
            'needs_confirmation' => ['sometimes', 'boolean'],
        ]);

        $transactions = $request->user()->transactions()
            ->when($filters['from'] ?? null, fn ($q, $from) => $q->where('occurred_on', '>=', $from))
            ->when($filters['to'] ?? null, fn ($q, $to) => $q->where('occurred_on', '<=', $to))
            ->when($filters['account_id'] ?? null, fn ($q, $id) => $q->where('account_id', $id))
            ->when($filters['category_id'] ?? null, fn ($q, $id) => $q->where('category_id', $id))
            ->when($filters['direction'] ?? null, fn ($q, $direction) => $q->where('direction', $direction))
            ->when($request->boolean('needs_confirmation'), fn ($q) => $q->where('is_auto', true)->whereNull('confirmed_at'))
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

    /** A repeat of an earlier create (same client_ref) returns that transaction with 200 instead of a duplicate. */
    public function store(TransactionRequest $request): JsonResponse
    {
        $ref = $request->validated('client_ref');
        $existing = $ref ? $request->user()->transactions()->where('client_ref', $ref)->first() : null;
        if ($existing) {
            return TransactionResource::make($existing)->response()->setStatusCode(200);
        }

        try {
            $transaction = $request->user()->transactions()->create($request->payload());
        } catch (UniqueConstraintViolationException) {
            // The same entry arrived twice at once; the other request saved it.
            return TransactionResource::make($request->user()->transactions()->where('client_ref', $ref)->firstOrFail())->response();
        }

        return TransactionResource::make($transaction)->response()->setStatusCode(201);
    }

    public function show(Transaction $transaction): TransactionResource
    {
        return TransactionResource::make($transaction);
    }

    /** Payments of dues and lending entries are edited where they came from, so the two never disagree. */
    public function update(TransactionRequest $request, Transaction $transaction): TransactionResource
    {
        $owner = match ($transaction->source_type) {
            Due::TRANSACTION_SOURCE => 'Dues',
            Lending::TRANSACTION_SOURCE, LendingRepayment::TRANSACTION_SOURCE => 'Lent money',
            default => null,
        };
        abort_if($owner !== null, 409, "This transaction is managed from {$owner}. Change it there, or delete it here.");

        // Editing an auto-posted salary is as good as confirming it.
        $transaction->update([...collect($request->payload())->except('client_ref')->all(), 'confirmed_at' => $transaction->is_auto ? now() : null]);

        return TransactionResource::make($transaction);
    }

    /**
     * Accepts an auto-posted transaction as right. A variable salary can take its actual amount here.
     */
    public function confirm(Request $request, Transaction $transaction): TransactionResource
    {
        abort_unless($transaction->is_auto, 409, 'Only automatic entries need confirming.');
        $data = $request->validate(['amount' => ['sometimes', 'integer', 'min:1', 'max:100000000000']]);
        if (isset($data['amount']) && $data['amount'] !== $transaction->amount) {
            abort_unless($transaction->source_type === Transaction::SOURCE_INCOME, 409, 'Only a salary’s amount can be changed here. Reopen the due to pay a different amount.');
            $transaction->amount = $data['amount'];
        }
        $transaction->confirmed_at = now();
        $transaction->save();

        return TransactionResource::make($transaction);
    }

    /**
     * Deleting an auto-posted salary stops automation posting it again for that month. Deleting a due's payment
     * reopens the due; deleting a repayment's credit removes the repayment; deleting a
     * lending's debit leaves the lending in place, just no longer drawn from an account.
     */
    public function destroy(Request $request, Transaction $transaction, DuePayments $payments, LendingBook $book): Response
    {
        $user = $request->user();
        $due = $transaction->source_type === Due::TRANSACTION_SOURCE
            ? $user->dues()->where('transaction_id', $transaction->id)->first()
            : null;
        $repayment = $transaction->source_type === LendingRepayment::TRANSACTION_SOURCE
            ? LendingRepayment::query()->where('transaction_id', $transaction->id)->first()
            : null;
        $lending = $transaction->source_type === Lending::TRANSACTION_SOURCE
            ? $user->lendings()->where('transaction_id', $transaction->id)->first()
            : null;

        match (true) {
            $due !== null => $payments->reopen($due),
            $repayment !== null => $book->deleteRepayment($repayment),
            default => DB::transaction(function () use ($user, $lending, $transaction) {
                if ($transaction->is_auto && $transaction->source_type === Transaction::SOURCE_INCOME) {
                    AutomationOptOut::record($user->id, Transaction::SOURCE_INCOME, $transaction->source_id, $transaction->period);
                }
                $lending?->forceFill(['account_id' => null, 'transaction_id' => null])->save();
                $transaction->delete();
            }),
        };

        return response()->noContent();
    }
}
