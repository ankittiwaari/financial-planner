<?php

namespace App\Http\Controllers\Api\V1;

use App\Enums\LoanStatus;
use App\Http\Controllers\Controller;
use App\Http\Requests\LoanRequest;
use App\Http\Resources\LoanResource;
use App\Models\Loan;
use App\Services\DuesGenerator;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\AnonymousResourceCollection;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;

class LoanController extends Controller
{
    public function __construct(private readonly DuesGenerator $dues) {}

    /** Active loans first, then closed ones. */
    public function index(Request $request): AnonymousResourceCollection
    {
        $loans = $request->user()->loans()->orderBy('status')->orderBy('name')->get();

        return LoanResource::collection($loans);
    }

    public function store(LoanRequest $request): JsonResponse
    {
        $loan = DB::transaction(function () use ($request) {
            $loan = $request->user()->loans()->create($request->payload());
            $this->dues->refresh($loan);

            return $loan;
        });

        return LoanResource::make($loan->refresh())->response()->setStatusCode(201);
    }

    public function show(Loan $loan): LoanResource
    {
        return LoanResource::make($loan);
    }

    public function update(LoanRequest $request, Loan $loan): LoanResource
    {
        DB::transaction(function () use ($request, $loan) {
            $loan->update($request->payload());
            $this->dues->refresh($loan);
        });

        return LoanResource::make($loan->refresh());
    }

    /** Unpaid dues go with it; paid EMIs stay in dues and transactions as history. */
    public function destroy(Loan $loan): Response
    {
        DB::transaction(function () use ($loan) {
            $this->dues->discard($loan);
            $loan->delete();
        });

        return response()->noContent();
    }

    /** The amortisation table: principal/interest split and outstanding after each EMI. */
    public function schedule(Request $request, Loan $loan): JsonResponse
    {
        $rows = array_map(fn (array $row) => [
            ...$row,
            'due_date' => $row['due_date']->toDateString(),
        ], $loan->progress($request->user()->today())['schedule']);

        return response()->json(['data' => $rows]);
    }

    /** Marks the loan repaid (e.g. foreclosed early) and drops its unpaid dues. */
    public function close(Request $request, Loan $loan): LoanResource
    {
        DB::transaction(function () use ($request, $loan) {
            $this->dues->discard($loan);
            $loan->forceFill(['status' => LoanStatus::Closed, 'closed_on' => $request->user()->today()])->save();
        });

        return LoanResource::make($loan->refresh());
    }
}
