<?php

namespace App\Http\Controllers\Api\V1;

use App\Enums\DueStatus;
use App\Http\Controllers\Controller;
use App\Http\Requests\PayDueRequest;
use App\Http\Resources\DueResource;
use App\Models\CardStatement;
use App\Models\Due;
use App\Services\DuePayments;
use App\Services\DuesGenerator;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\AnonymousResourceCollection;
use Illuminate\Validation\Rule;

class DueController extends Controller
{
    public function __construct(private readonly DuesGenerator $generator, private readonly DuePayments $payments) {}

    /**
     * Dues dated from..to (default: 30 days ago to 45 days ahead), oldest first. Without a status filter, overdue
     * dues are included however old they are.
     */
    public function index(Request $request): AnonymousResourceCollection
    {
        $filters = $request->validate([
            'from' => ['sometimes', 'date_format:Y-m-d'],
            'to' => ['sometimes', 'date_format:Y-m-d'],
            'status' => ['sometimes', Rule::in(['upcoming', 'overdue', 'paid', 'skipped'])],
        ]);
        $user = $request->user();
        $today = $user->today();
        // The window rolls forward every day; the scheduler does this too, but a read shouldn't wait for it.
        $this->generator->forUser($user);

        $from = isset($filters['from']) ? CarbonImmutable::parse($filters['from']) : $today->subDays(30);
        $to = isset($filters['to']) ? CarbonImmutable::parse($filters['to']) : $today->addDays(DuesGenerator::WINDOW_DAYS);
        // whereDate, not whereBetween: date columns may hold a time part (SQLite), which a plain upper bound would exclude.
        $inRange = fn ($q) => $q->whereDate('due_date', '>=', $from->toDateString())->whereDate('due_date', '<=', $to->toDateString());
        $overdue = fn ($q) => $q->unpaid()->where('due_date', '<', $today->toDateString());

        $dues = $user->dues()
            ->with(['source' => fn ($morph) => $morph->morphWith([CardStatement::class => ['card']])])
            ->when($filters['status'] ?? null, fn ($q, $status) => match ($status) {
                'overdue' => $q->where($overdue),
                'upcoming' => $q->where($inRange)->unpaid()->where('due_date', '>=', $today->toDateString()),
                default => $q->where($inRange)->where('status', $status),
            }, fn ($q) => $q->where(fn ($q) => $q->where($inRange)->orWhere($overdue)))
            ->orderBy('due_date')
            ->orderBy('id')
            ->get();

        return DueResource::collection($dues);
    }

    public function show(Due $due): DueResource
    {
        return DueResource::make($due);
    }

    /** Books a debit for the payment (defaults: the due's amount, today, the source's account) and marks it paid. */
    public function pay(PayDueRequest $request, Due $due): DueResource
    {
        $paidOn = $request->has('paid_on') ? CarbonImmutable::parse($request->input('paid_on')) : null;
        $amount = $request->has('amount') ? $request->integer('amount') : null;
        $accountId = $request->has('account_id') ? $request->integer('account_id') : null;

        return DueResource::make($this->payments->pay($request->user(), $due, $amount, $paidOn, $accountId));
    }

    public function skip(Due $due): DueResource
    {
        return DueResource::make($this->payments->skip($due));
    }

    /** Undoes a payment (deleting its transaction) or a skip. */
    public function reopen(Due $due): DueResource
    {
        abort_if($due->status === DueStatus::Upcoming, 409, "{$due->title} is already open.");

        return DueResource::make($this->payments->reopen($due));
    }
}
