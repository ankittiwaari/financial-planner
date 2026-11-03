<?php

namespace App\Http\Requests;

use App\Enums\EstimateMode;
use App\Models\CreditCard;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;

class CreditCardRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    /**
     * @return array<string, \Illuminate\Contracts\Validation\ValidationRule|array<mixed>|string>
     */
    public function rules(): array
    {
        $current = $this->route('credit_card');
        $currentAccountId = $current instanceof CreditCard ? $current->pay_from_account_id : null;

        return [
            'nickname' => ['required', 'string', 'max:40'],
            'last4' => ['nullable', 'string', 'regex:/^\d{4}$/'],
            'issuer' => ['nullable', 'string', 'max:40'],
            'credit_limit' => ['nullable', 'integer', 'min:1', 'max:100000000000'],
            'statement_day' => ['required', 'integer', 'between:1,31'],
            'due_day' => ['required', 'integer', 'between:1,31'],
            'pay_from_account_id' => [
                'required', 'integer',
                Rule::exists('accounts', 'id')
                    ->where('user_id', $this->user()->id)
                    ->where(fn ($q) => $q->whereNull('archived_at')->orWhere('id', $currentAccountId)),
            ],
            'estimate_mode' => ['sometimes', Rule::enum(EstimateMode::class)],
            'fixed_estimate' => ['nullable', 'integer', 'min:0', 'max:100000000000', 'required_if:estimate_mode,fixed'],
        ];
    }

    public function messages(): array
    {
        return [
            'last4.regex' => 'Enter just the last 4 digits of the card number.',
            'pay_from_account_id.exists' => 'Choose one of your active accounts.',
            'fixed_estimate.required_if' => 'Enter the amount to expect each month.',
        ];
    }

    public function attributes(): array
    {
        return ['pay_from_account_id' => 'account'];
    }

    /** @return array<string, mixed> */
    public function payload(): array
    {
        $data = [
            'last4' => null, 'issuer' => null, 'credit_limit' => null, 'estimate_mode' => EstimateMode::LastStatement->value,
            'fixed_estimate' => null, ...$this->validated(),
        ];
        if ($data['estimate_mode'] !== EstimateMode::Fixed->value) {
            $data['fixed_estimate'] = null;
        }

        return $data;
    }
}
