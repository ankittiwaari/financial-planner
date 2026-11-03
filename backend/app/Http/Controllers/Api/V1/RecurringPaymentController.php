<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Requests\RecurringPaymentRequest;
use App\Http\Resources\RecurringPaymentResource;
use App\Models\RecurringPayment;
use App\Services\DuesGenerator;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\AnonymousResourceCollection;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;

class RecurringPaymentController extends Controller
{
    public function __construct(private readonly DuesGenerator $dues) {}

    public function index(Request $request): AnonymousResourceCollection
    {
        return RecurringPaymentResource::collection($request->user()->recurringPayments()->orderBy('name')->get());
    }

    public function store(RecurringPaymentRequest $request): JsonResponse
    {
        $payment = DB::transaction(function () use ($request) {
            $payment = $request->user()->recurringPayments()->create([
                'active_from' => $request->user()->today()->toDateString(),
                ...$request->payload(),
            ]);
            $this->dues->refresh($payment);

            return $payment;
        });

        return RecurringPaymentResource::make($payment->refresh())->response()->setStatusCode(201);
    }

    public function show(RecurringPayment $recurringPayment): RecurringPaymentResource
    {
        return RecurringPaymentResource::make($recurringPayment);
    }

    public function update(RecurringPaymentRequest $request, RecurringPayment $recurringPayment): RecurringPaymentResource
    {
        DB::transaction(function () use ($request, $recurringPayment) {
            $recurringPayment->update($request->payload());
            $this->dues->refresh($recurringPayment);
        });

        return RecurringPaymentResource::make($recurringPayment->refresh());
    }

    /** Unpaid dues go with it; paid ones stay in dues and transactions as history. */
    public function destroy(RecurringPayment $recurringPayment): Response
    {
        DB::transaction(function () use ($recurringPayment) {
            $this->dues->discard($recurringPayment);
            $recurringPayment->delete();
        });

        return response()->noContent();
    }
}
