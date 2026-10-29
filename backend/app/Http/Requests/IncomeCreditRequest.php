<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

class IncomeCreditRequest extends FormRequest
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
            'period' => ['required', 'string', 'regex:/^\d{4}-(0[1-9]|1[0-2])$/'],
            'amount' => ['sometimes', 'integer', 'min:1', 'max:100000000000'],
            'occurred_on' => ['sometimes', 'date_format:Y-m-d'],
        ];
    }

    public function messages(): array
    {
        return ['period.regex' => 'The period must look like 2026-10.'];
    }
}
