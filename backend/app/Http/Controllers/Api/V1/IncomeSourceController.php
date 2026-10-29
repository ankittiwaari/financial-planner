<?php

namespace App\Http\Controllers\Api\V1;

use App\Enums\Direction;
use App\Http\Controllers\Controller;
use App\Http\Requests\IncomeCreditRequest;
use App\Http\Requests\IncomeSourceRequest;
use App\Http\Resources\IncomeSourceResource;
use App\Http\Resources\TransactionResource;
use App\Models\IncomeSource;
use App\Models\Transaction;
use App\Support\DefaultCategories;
use Carbon\CarbonImmutable;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\AnonymousResourceCollection;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;
use Illuminate\Validation\ValidationException;

class IncomeSourceController extends Controller
{
    public function index(Request $request): AnonymousResourceCollection
    {
        $sources = $request->user()->incomeSources()
            ->withMax('credits', 'period')
            ->orderBy('name')
            ->get();

        return IncomeSourceResource::collection($sources);
    }

    public function store(IncomeSourceRequest $request): JsonResponse
    {
        $source = $request->user()->incomeSources()->create([
            'active_from' => $request->user()->today()->toDateString(),
            ...$request->payload(),
        ]);

        return IncomeSourceResource::make($source->refresh())->response()->setStatusCode(201);
    }

    public function show(IncomeSource $incomeSource): IncomeSourceResource
    {
        return IncomeSourceResource::make($incomeSource);
    }

    public function update(IncomeSourceRequest $request, IncomeSource $incomeSource): IncomeSourceResource
    {
        $incomeSource->update($request->payload());

        return IncomeSourceResource::make($incomeSource->refresh());
    }

    /** Past credits stay in the ledger as ordinary income; only their link to this source goes. */
    public function destroy(IncomeSource $incomeSource): Response
    {
        DB::transaction(function () use ($incomeSource) {
            $incomeSource->credits()->update(['source_type' => null, 'source_id' => null]);
            $incomeSource->delete();
        });

        return response()->noContent();
    }

    /** Logs this source's credit for one pay period. At most one per period. */
    public function credit(IncomeCreditRequest $request, IncomeSource $incomeSource): JsonResponse
    {
        $period = $request->string('period')->value();
        $label = CarbonImmutable::createFromFormat('Y-m-d', $period.'-01')->format('M Y');

        if ($incomeSource->account()->value('archived_at') !== null) {
            throw ValidationException::withMessages([
                'account_id' => "{$incomeSource->name} goes into an archived account. Edit it to pick another account.",
            ]);
        }

        $conflict = fn () => abort(409, "{$incomeSource->name} is already logged for {$label}.");
        if ($incomeSource->credits()->where('period', $period)->exists()) {
            $conflict();
        }

        try {
            $transaction = $request->user()->transactions()->create([
                'account_id' => $incomeSource->account_id,
                'category_id' => DefaultCategories::salaryFor($request->user())->id,
                'direction' => Direction::Credit,
                'amount' => $request->integer('amount', $incomeSource->amount),
                'occurred_on' => $request->input('occurred_on', $incomeSource->payDateFor($period)->toDateString()),
                'description' => $incomeSource->name,
                'source_type' => Transaction::SOURCE_INCOME,
                'source_id' => $incomeSource->id,
                'period' => $period,
                'is_auto' => false,
            ]);
        } catch (UniqueConstraintViolationException) {
            $conflict(); // a simultaneous request won the race
        }

        return TransactionResource::make($transaction)->response()->setStatusCode(201);
    }
}
