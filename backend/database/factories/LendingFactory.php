<?php

namespace Database\Factories;

use App\Models\User;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<\App\Models\Lending>
 */
class LendingFactory extends Factory
{
    public function definition(): array
    {
        return [
            'user_id' => User::factory(),
            'person_name' => 'Ravi',
            'amount' => 1_000_000, // ₹10,000
            'lent_on' => '2026-09-15',
        ];
    }
}
