<?php

namespace Database\Factories;

use App\Enums\Frequency;
use App\Models\Account;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<\App\Models\RecurringPayment>
 */
class RecurringPaymentFactory extends Factory
{
    public function definition(): array
    {
        return [
            'account_id' => Account::factory(),
            'user_id' => fn (array $attributes) => Account::find($attributes['account_id'])->user_id,
            'name' => 'Rent',
            'amount' => 2_500_000,
            'frequency' => Frequency::Monthly,
            'day' => 1,
            'active_from' => '2026-01-01',
        ];
    }
}
