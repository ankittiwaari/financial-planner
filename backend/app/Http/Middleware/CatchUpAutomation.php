<?php

namespace App\Http\Middleware;

use App\Services\DailyAutomation;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Before the signed-in user's first request of the day is answered, does that day's automation for them (dues,
 * payday salary, auto-debits), so what the app shows never depends on the cron job having run. Later requests that
 * day cost one indexed lookup.
 */
class CatchUpAutomation
{
    public function __construct(private readonly DailyAutomation $automation) {}

    public function handle(Request $request, Closure $next): Response
    {
        if ($user = $request->user()) {
            $this->automation->catchUp($user);
        }

        return $next($request);
    }
}
