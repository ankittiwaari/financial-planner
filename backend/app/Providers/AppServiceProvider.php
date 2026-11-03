<?php

namespace App\Providers;

use App\Models\Account;
use App\Models\CardStatement;
use App\Models\Category;
use App\Models\CreditCard;
use App\Models\Due;
use App\Models\IncomeSource;
use App\Models\Lending;
use App\Models\LendingRepayment;
use App\Models\Loan;
use App\Models\RecurringPayment;
use App\Models\Transaction;
use Illuminate\Cache\RateLimiting\Limit;
use Illuminate\Database\Eloquent\Relations\Relation;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\Facades\Route;
use Illuminate\Support\ServiceProvider;

class AppServiceProvider extends ServiceProvider
{
    /**
     * Register any application services.
     */
    public function register(): void
    {
        //
    }

    /**
     * Bootstrap any application services.
     */
    public function boot(): void
    {
        // dues.source_type stores these short names rather than class names. Not enforced, so Sanctum's tokenable
        // rows keep their existing class names.
        Relation::morphMap([
            Due::SOURCE_LOAN => Loan::class,
            Due::SOURCE_CARD => CardStatement::class,
            Due::SOURCE_RECURRING => RecurringPayment::class,
        ]);

        // Slows password guessing: 5 attempts a minute per email + IP.
        RateLimiter::for('login', function (Request $request) {
            return Limit::perMinute(5)->by(strtolower((string) $request->input('email')).'|'.$request->ip());
        });

        // Route models resolve through the signed-in user, so another user's id is a plain 404
        // (before validation runs). Auth middleware runs before route binding.
        $owned = [
            'account' => Account::class,
            'category' => Category::class,
            'transaction' => Transaction::class,
            'income_source' => IncomeSource::class,
            'loan' => Loan::class,
            'credit_card' => CreditCard::class,
            'recurring_payment' => RecurringPayment::class,
            'due' => Due::class,
            'lending' => Lending::class,
        ];
        foreach ($owned as $parameter => $model) {
            Route::bind($parameter, fn (string $value) => $model::query()
                ->where('user_id', request()->user()?->id)
                ->findOrFail((int) $value));
        }
        // Repayments belong to the user through their lending, and to the lending in the URL.
        Route::bind('repayment', fn (string $value) => LendingRepayment::query()
            ->whereHas('lending', fn ($q) => $q->where('user_id', request()->user()?->id))
            ->where('lending_id', (int) request()->route()->originalParameter('lending'))
            ->findOrFail((int) $value));
    }
}
