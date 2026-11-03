<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

class PayDueRequest extends FormRequest
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
            'amount' => ['sometimes', 'integer', 'min:1', 'max:100000000000'],
            'paid_on' => ['sometimes', 'date_format:Y-m-d'],
            'account_id' => ['sometimes', 'integer'], // ownership and archived state are checked when paying
        ];
    }
}
