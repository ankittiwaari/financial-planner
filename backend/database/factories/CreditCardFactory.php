<?php

namespace Database\Factories;

use App\Enums\EstimateMode;
use App\Models\Account;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends Factory<\App\Models\CreditCard>
 */
class CreditCardFactory extends Factory
{
    public function definition(): array
    {
        return [
            'pay_from_account_id' => Account::factory(),
            'user_id' => fn (array $attributes) => Account::find($attributes['pay_from_account_id'])->user_id,
            'nickname' => 'ICICI Amazon Pay',
            'last4' => '4321',
            'issuer' => 'ICICI',
            'statement_day' => 28,
            'due_day' => 17,
            'estimate_mode' => EstimateMode::LastStatement,
        ];
    }
}
