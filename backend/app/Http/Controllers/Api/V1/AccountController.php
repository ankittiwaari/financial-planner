<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Requests\AccountRequest;
use App\Http\Resources\AccountResource;
use App\Models\Account;
use App\Models\User;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\AnonymousResourceCollection;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;

class AccountController extends Controller
{
    public function index(Request $request): AnonymousResourceCollection
    {
        $accounts = $request->user()->accounts()
            ->withBalance()
            ->unless($request->boolean('include_archived'), fn ($q) => $q->active())
            ->orderByDesc('is_primary')
            ->orderBy('name')
            ->get();

        return AccountResource::collection($accounts);
    }

    public function store(AccountRequest $request): JsonResponse
    {
        $user = $request->user();
        $account = DB::transaction(function () use ($request, $user) {
            $isFirst = ! $user->accounts()->active()->exists();
            $account = $user->accounts()->create([
                ...$request->validated(),
                'is_primary' => $isFirst || $request->boolean('is_primary'),
            ]);
            if ($account->is_primary) {
                $this->makeOnlyPrimary($user, $account);
            }

            return $account;
        });

        return AccountResource::make($this->fresh($account))->response()->setStatusCode(201);
    }

    public function show(Account $account): AccountResource
    {
        return AccountResource::make($this->fresh($account));
    }

    public function update(AccountRequest $request, Account $account): AccountResource
    {
        DB::transaction(function () use ($request, $account) {
            // Primary moves only by choosing another account; unticking it alone would leave none.
            $makePrimary = $request->boolean('is_primary') && ! $account->isArchived();
            $account->update([...$request->safe()->except('is_primary'), 'is_primary' => $account->is_primary || $makePrimary]);
            if ($makePrimary) {
                $this->makeOnlyPrimary($request->user(), $account);
            }
        });

        return AccountResource::make($this->fresh($account));
    }

    /** Archives: history stays, the account just stops taking new entries. */
    public function destroy(Request $request, Account $account): Response
    {
        DB::transaction(function () use ($request, $account) {
            $wasPrimary = $account->is_primary;
            $account->forceFill(['archived_at' => now(), 'is_primary' => false])->save();

            if ($wasPrimary) {
                $next = $request->user()->accounts()->active()->oldest('id')->first();
                $next?->forceFill(['is_primary' => true])->save();
            }
        });

        return response()->noContent();
    }

    private function makeOnlyPrimary(User $user, Account $account): void
    {
        $user->accounts()->whereKeyNot($account->id)->where('is_primary', true)->update(['is_primary' => false]);
    }

    private function fresh(Account $account): Account
    {
        return Account::query()->withBalance()->findOrFail($account->id);
    }
}
