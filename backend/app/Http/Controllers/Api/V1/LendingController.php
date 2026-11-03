<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Requests\LendingRequest;
use App\Http\Requests\RepaymentRequest;
use App\Http\Resources\LendingResource;
use App\Models\Lending;
use App\Models\LendingRepayment;
use App\Services\LendingBook;
use Carbon\CarbonImmutable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\AnonymousResourceCollection;
use Illuminate\Http\Response;
use Illuminate\Validation\Rule;

class LendingController extends Controller
{
    public function __construct(private readonly LendingBook $book) {}

    /** Still-owed first, then newest. Filter by person (any spacing or case) or by status. */
    public function index(Request $request): AnonymousResourceCollection
    {
        $filters = $request->validate([
            'person' => ['sometimes', 'string', 'max:60'],
            'status' => ['sometimes', Rule::in(['outstanding', 'settled', 'written_off'])],
        ]);

        $lendings = $request->user()->lendings()
            ->with(['repayments' => fn ($q) => $q->orderBy('received_on')->orderBy('id')])
            ->withSum('repayments as repaid_sum', 'amount')
            ->when($filters['status'] ?? null, fn ($q, $status) => $status === 'outstanding' ? $q->outstanding() : $q->where('status', $status))
            ->orderByRaw("case when status in ('open', 'partially_repaid') then 0 else 1 end")
            ->orderByDesc('lent_on')
            ->orderByDesc('id')
            ->get()
            ->when($filters['person'] ?? null, fn ($all, $person) => $all
                ->filter(fn (Lending $l) => LendingBook::personKey($l->person_name) === LendingBook::personKey($person))
                ->values());

        return LendingResource::collection($lendings);
    }

    /** Receivables: totals and a balance per person. */
    public function summary(Request $request): JsonResponse
    {
        return response()->json(['data' => $this->book->summary($request->user())]);
    }

    public function store(LendingRequest $request): JsonResponse
    {
        $lending = $this->book->lend($request->user(), $request->payload());

        return LendingResource::make($lending->refresh())->response()->setStatusCode(201);
    }

    public function show(Lending $lending): LendingResource
    {
        return LendingResource::make($lending);
    }

    public function update(LendingRequest $request, Lending $lending): LendingResource
    {
        return LendingResource::make($this->book->update($lending, $request->payload())->refresh());
    }

    public function destroy(Lending $lending): Response
    {
        $this->book->delete($lending);

        return response()->noContent();
    }

    public function repay(RepaymentRequest $request, Lending $lending): JsonResponse
    {
        $receivedOn = $request->has('received_on')
            ? CarbonImmutable::parse($request->input('received_on'))
            : $request->user()->today();
        $accountId = $request->filled('account_id') ? $request->integer('account_id') : null;

        $this->book->repay($request->user(), $lending, $request->integer('amount'), $receivedOn, $accountId);

        return LendingResource::make($lending->refresh())->response()->setStatusCode(201);
    }

    public function deleteRepayment(Lending $lending, LendingRepayment $repayment): LendingResource
    {
        return LendingResource::make($this->book->deleteRepayment($repayment)->refresh());
    }

    public function writeOff(Request $request, Lending $lending): LendingResource
    {
        return LendingResource::make($this->book->writeOff($lending, $request->user()->today()));
    }

    public function reopen(Lending $lending): LendingResource
    {
        return LendingResource::make($this->book->reopen($lending)->refresh());
    }
}
