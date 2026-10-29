<?php

namespace Database\Factories;

use App\Enums\Direction;
use App\Models\Account;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<\App\Models\Transaction>
 */
class TransactionFactory extends Factory
{
    public function definition(): array
    {
        return [
            'account_id' => Account::factory(),
            'user_id' => fn (array $attributes) => Account::find($attributes['account_id'])->user_id,
            'direction' => Direction::Debit,
            'amount' => fake()->numberBetween(100, 500_000),
            'occurred_on' => '2026-10-15',
        ];
    }

    public function credit(): static
    {
        return $this->state(fn () => ['direction' => Direction::Credit]);
    }
}
