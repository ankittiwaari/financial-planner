<?php

use App\Http\Controllers\Api\V1\AccountController;
use App\Http\Controllers\Api\V1\AuthController;
use App\Http\Controllers\Api\V1\AutomationController;
use App\Http\Controllers\Api\V1\CategoryController;
use App\Http\Controllers\Api\V1\CreditCardController;
use App\Http\Controllers\Api\V1\DashboardController;
use App\Http\Controllers\Api\V1\DueController;
use App\Http\Controllers\Api\V1\IncomeSourceController;
use App\Http\Controllers\Api\V1\LendingController;
use App\Http\Controllers\Api\V1\LoanController;
use App\Http\Controllers\Api\V1\RecurringPaymentController;
use App\Http\Controllers\Api\V1\ReminderSettingsController;
use App\Http\Controllers\Api\V1\SyncController;
use App\Http\Controllers\Api\V1\TransactionController;
use App\Http\Middleware\CatchUpAutomation;
use Illuminate\Support\Facades\Route;

// Contract: openapi.yaml. Single-user app, so there is no public sign-up; create the owner with `php artisan db:seed`.
Route::prefix('v1')->group(function () {
    Route::get('health', fn () => ['status' => 'ok']);

    Route::post('auth/login', [AuthController::class, 'login'])->middleware('throttle:login');

    // CatchUpAutomation: the day's salary posting and auto-debits happen on the first request, not only via cron.
    Route::middleware(['auth:sanctum', CatchUpAutomation::class])->group(function () {
        Route::get('me', [AuthController::class, 'me']);
        Route::post('auth/logout', [AuthController::class, 'logout']);

        Route::get('dashboard', [DashboardController::class, 'show']);
        Route::get('sync', [SyncController::class, 'show']);
        Route::get('settings/reminders', [ReminderSettingsController::class, 'show']);
        Route::put('settings/reminders', [ReminderSettingsController::class, 'update']);

        Route::apiResource('accounts', AccountController::class);
        Route::apiResource('categories', CategoryController::class)->except('show');
        Route::get('transactions/summary', [TransactionController::class, 'summary']);
        Route::apiResource('transactions', TransactionController::class);
        Route::post('transactions/{transaction}/confirm', [TransactionController::class, 'confirm']);
        Route::get('automation/runs', [AutomationController::class, 'runs']);
        Route::apiResource('income-sources', IncomeSourceController::class);
        Route::post('income-sources/{income_source}/credits', [IncomeSourceController::class, 'credit']);

        Route::apiResource('loans', LoanController::class);
        Route::get('loans/{loan}/schedule', [LoanController::class, 'schedule']);
        Route::post('loans/{loan}/close', [LoanController::class, 'close']);
        Route::apiResource('credit-cards', CreditCardController::class);
        Route::get('credit-cards/{credit_card}/statements', [CreditCardController::class, 'statements']);
        Route::post('credit-cards/{credit_card}/statements', [CreditCardController::class, 'enterStatement']);
        Route::apiResource('recurring-payments', RecurringPaymentController::class);
        Route::get('dues', [DueController::class, 'index']);
        Route::get('dues/{due}', [DueController::class, 'show']);
        Route::post('dues/{due}/pay', [DueController::class, 'pay']);
        Route::post('dues/{due}/skip', [DueController::class, 'skip']);
        Route::post('dues/{due}/reopen', [DueController::class, 'reopen']);

        Route::get('lendings/summary', [LendingController::class, 'summary']);
        Route::apiResource('lendings', LendingController::class);
        Route::post('lendings/{lending}/repayments', [LendingController::class, 'repay']);
        Route::delete('lendings/{lending}/repayments/{repayment}', [LendingController::class, 'deleteRepayment']);
        Route::post('lendings/{lending}/write-off', [LendingController::class, 'writeOff']);
        Route::post('lendings/{lending}/reopen', [LendingController::class, 'reopen']);
    });
});
