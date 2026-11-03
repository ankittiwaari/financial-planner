<?php

use App\Models\User;
use App\Services\DailyAutomation;
use App\Services\DuesGenerator;
use Illuminate\Foundation\Inspiring;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Schedule;

Artisan::command('inspire', function () {
    $this->comment(Inspiring::quote());
})->purpose('Display an inspiring quote');

Artisan::command('dues:generate', function (DuesGenerator $generator) {
    User::query()->each(fn (User $user) => $generator->forUser($user));
    $this->info('Dues generated for the next '.DuesGenerator::WINDOW_DAYS.' days.');
})->purpose('Materialise dues for the coming window from loans, cards and recurring payments');

Artisan::command('automation:run', function (DailyAutomation $automation) {
    User::query()->each(function (User $user) use ($automation) {
        foreach ($automation->run($user) as $job => $result) {
            $this->line("user {$user->id} {$job}: ".(is_array($result) ? json_encode($result) : "FAILED {$result}"));
        }
    });
})->purpose('Daily automation: generate dues, post salaries on payday, settle auto-debits');

// Hourly, so each user's new day is picked up soon after their midnight whatever their timezone. Every job is
// idempotent: re-runs within the day do nothing.
Schedule::command('automation:run')->hourly()->withoutOverlapping();

Schedule::command('backup:database')->dailyAt('02:30')->timezone('Asia/Kolkata')->withoutOverlapping();
