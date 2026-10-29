<?php

use App\Http\Controllers\Api\V1\AccountController;
use App\Http\Controllers\Api\V1\AuthController;
use App\Http\Controllers\Api\V1\CategoryController;
use App\Http\Controllers\Api\V1\IncomeSourceController;
use App\Http\Controllers\Api\V1\TransactionController;
use Illuminate\Support\Facades\Route;

// Contract: openapi.yaml. Single-user app, so there is no public sign-up; create the owner with `php artisan db:seed`.
Route::prefix('v1')->group(function () {
    Route::get('health', fn () => ['status' => 'ok']);

    Route::post('auth/login', [AuthController::class, 'login'])->middleware('throttle:login');

    Route::middleware('auth:sanctum')->group(function () {
        Route::get('me', [AuthController::class, 'me']);
        Route::post('auth/logout', [AuthController::class, 'logout']);

        Route::apiResource('accounts', AccountController::class);
        Route::apiResource('categories', CategoryController::class)->except('show');
        Route::get('transactions/summary', [TransactionController::class, 'summary']);
        Route::apiResource('transactions', TransactionController::class);
        Route::apiResource('income-sources', IncomeSourceController::class);
        Route::post('income-sources/{income_source}/credits', [IncomeSourceController::class, 'credit']);
    });
});
