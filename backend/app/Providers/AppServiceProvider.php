<?php

namespace App\Providers;

use App\Models\Account;
use App\Models\Category;
use App\Models\IncomeSource;
use App\Models\Transaction;
use Illuminate\Cache\RateLimiting\Limit;
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
        ];
        foreach ($owned as $parameter => $model) {
            Route::bind($parameter, fn (string $value) => $model::query()
                ->where('user_id', request()->user()?->id)
                ->findOrFail((int) $value));
        }
    }
}
