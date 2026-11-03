<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Requests\CardStatementRequest;
use App\Http\Requests\CreditCardRequest;
use App\Http\Resources\CardStatementResource;
use App\Http\Resources\CreditCardResource;
use App\Models\CreditCard;
use App\Services\DuesGenerator;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\AnonymousResourceCollection;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;

class CreditCardController extends Controller
{
    public function __construct(private readonly DuesGenerator $dues) {}

    public function index(Request $request): AnonymousResourceCollection
    {
        $cards = $request->user()->creditCards()->with('latestStatement')->orderBy('nickname')->get();

        return CreditCardResource::collection($cards);
    }

    public function store(CreditCardRequest $request): JsonResponse
    {
        $card = DB::transaction(function () use ($request) {
            $card = $request->user()->creditCards()->create($request->payload());
            $this->dues->refresh($card);

            return $card;
        });

        return CreditCardResource::make($card->load('latestStatement'))->response()->setStatusCode(201);
    }

    public function show(CreditCard $creditCard): CreditCardResource
    {
        return CreditCardResource::make($creditCard->load('latestStatement'));
    }

    public function update(CreditCardRequest $request, CreditCard $creditCard): CreditCardResource
    {
        DB::transaction(function () use ($request, $creditCard) {
            $creditCard->update($request->payload());
            $this->dues->refresh($creditCard);
        });

        return CreditCardResource::make($creditCard->load('latestStatement'));
    }

    /** Statements and unpaid bills go with it; paid bills stay in dues and transactions as history. */
    public function destroy(CreditCard $creditCard): Response
    {
        DB::transaction(function () use ($creditCard) {
            $this->dues->discard($creditCard);
            $creditCard->delete();
        });

        return response()->noContent();
    }

    /** Newest first. */
    public function statements(CreditCard $creditCard): AnonymousResourceCollection
    {
        return CardStatementResource::collection($creditCard->statements()->orderByDesc('period')->get());
    }

    /**
     * Enters the real bill for a period, replacing the estimate if there was one. Its due takes the new amount,
     * unless that due is already paid.
     */
    public function enterStatement(CardStatementRequest $request, CreditCard $creditCard): JsonResponse
    {
        $period = $request->string('period')->value();

        [$statement, $created] = DB::transaction(function () use ($request, $creditCard, $period) {
            $statement = $creditCard->statements()->firstOrNew(['period' => $period]);
            $created = ! $statement->exists;
            $statement->fill([
                'statement_date' => $request->input('statement_date', $creditCard->statementDateFor($period)->toDateString()),
                'due_date' => $request->input('due_date', $creditCard->dueDateFor($period)->toDateString()),
                'total_due' => $request->integer('total_due'),
                'min_due' => $request->input('min_due'),
                'status' => 'entered',
            ]);
            $statement->addPayment(0); // saves, and keeps a paid status if payments already cover the bill
            $this->dues->syncStatement($statement->setRelation('card', $creditCard));
            // Later estimates are based on this bill.
            $this->dues->forCard($creditCard, $request->user()->today(), $request->user()->today()->addDays(DuesGenerator::WINDOW_DAYS));

            return [$statement->refresh(), $created];
        });

        return CardStatementResource::make($statement)->response()->setStatusCode($created ? 201 : 200);
    }
}
