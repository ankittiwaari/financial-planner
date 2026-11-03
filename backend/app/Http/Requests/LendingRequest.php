<?php

namespace App\Http\Requests;

use App\Models\Lending;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;

class LendingRequest extends FormRequest
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
        $current = $this->route('lending');
        $currentAccountId = $current instanceof Lending ? $current->account_id : null;

        return [
            'person_name' => ['required', 'string', 'max:60'],
            'contact' => ['nullable', 'string', 'max:60'],
            'amount' => ['required', 'integer', 'min:1', 'max:100000000000'],
            'lent_on' => ['required', 'date_format:Y-m-d'],
            'expected_by' => ['nullable', 'date_format:Y-m-d', 'after_or_equal:lent_on'],
            'notes' => ['nullable', 'string', 'max:200'],
            // Null: the money didn't come out of a tracked account, so no transaction is booked.
            'account_id' => [
                'nullable', 'integer',
                Rule::exists('accounts', 'id')
                    ->where('user_id', $this->user()->id)
                    ->where(fn ($q) => $q->whereNull('archived_at')->orWhere('id', $currentAccountId)),
            ],
        ];
    }

    public function messages(): array
    {
        return [
            'account_id.exists' => 'Choose one of your active accounts.',
            'expected_by.after_or_equal' => 'The date you expect it back can’t be before the date you lent it.',
        ];
    }

    /**
     * Optional fields absent from the body are cleared, matching PUT semantics.
     *
     * @return array<string, mixed>
     */
    public function payload(): array
    {
        $data = ['contact' => null, 'expected_by' => null, 'notes' => null, 'account_id' => null, ...$this->validated()];
        $data['person_name'] = preg_replace('/\s+/', ' ', trim($data['person_name']));

        return $data;
    }
}
