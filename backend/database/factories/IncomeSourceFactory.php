<?php

namespace Database\Factories;

use App\Enums\PayRule;
use App\Enums\WeekendShift;
use App\Models\Account;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<\App\Models\IncomeSource>
 */
class IncomeSourceFactory extends Factory
{
    public function definition(): array
    {
        return [
            'account_id' => Account::factory(),
            'user_id' => fn (array $attributes) => Account::find($attributes['account_id'])->user_id,
            'name' => 'Salary - Acme',
            'amount' => 12_000_000,
            'pay_rule' => PayRule::LastWorkingDay,
            'weekend_shift' => WeekendShift::Before,
            'active_from' => '2026-01-01',
        ];
    }
}
