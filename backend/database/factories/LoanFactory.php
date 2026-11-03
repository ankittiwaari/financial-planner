<?php

namespace Database\Factories;

use App\Enums\LoanType;
use App\Models\Account;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<\App\Models\Loan>
 */
class LoanFactory extends Factory
{
    public function definition(): array
    {
        return [
            'account_id' => Account::factory(),
            'user_id' => fn (array $attributes) => Account::find($attributes['account_id'])->user_id,
            'name' => 'HDFC Home Loan',
            'lender' => 'HDFC',
            'type' => LoanType::Home,
            'principal' => 500_000_000, // ₹50 lakh
            'interest_rate' => 8.5,
            'tenure_months' => 240,
            'first_emi_date' => '2026-06-05',
            'auto_debit' => true,
        ];
    }
}
