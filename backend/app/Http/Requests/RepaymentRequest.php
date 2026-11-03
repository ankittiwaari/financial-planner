<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;

class RepaymentRequest extends FormRequest
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
        return [
            'amount' => ['required', 'integer', 'min:1', 'max:100000000000'],
            'received_on' => ['sometimes', 'date_format:Y-m-d'],
            // Null or absent: the money isn't tracked in an account, so no transaction is booked.
            'account_id' => [
                'nullable', 'integer',
                Rule::exists('accounts', 'id')->where('user_id', $this->user()->id)->whereNull('archived_at'),
            ],
        ];
    }

    public function messages(): array
    {
        return ['account_id.exists' => 'Choose one of your active accounts.'];
    }
}
