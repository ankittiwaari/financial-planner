<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

class CardStatementRequest extends FormRequest
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
            'total_due' => ['required', 'integer', 'min:0', 'max:100000000000'],
            'min_due' => ['nullable', 'integer', 'min:0', 'lte:total_due'],
            'statement_date' => ['sometimes', 'date_format:Y-m-d'],
            'due_date' => ['sometimes', 'date_format:Y-m-d'],
        ];
    }

    public function messages(): array
    {
        return [
            'period.regex' => 'The period must look like 2026-10.',
            'min_due.lte' => 'The minimum due can’t be more than the total.',
        ];
    }
}
