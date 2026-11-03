<?php

namespace App\Http\Requests;

use App\Enums\CategoryKind;
use App\Enums\Frequency;
use App\Models\RecurringPayment;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;

class RecurringPaymentRequest extends FormRequest
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
        $current = $this->route('recurring_payment');
        $currentAccountId = $current instanceof RecurringPayment ? $current->account_id : null;

        return [
            'name' => ['required', 'string', 'max:60'],
            'amount' => ['required', 'integer', 'min:1', 'max:100000000000'],
            'frequency' => ['required', Rule::enum(Frequency::class)],
            'day' => ['required', 'integer', 'between:1,31'],
            'account_id' => [
                'required', 'integer',
                Rule::exists('accounts', 'id')
                    ->where('user_id', $this->user()->id)
                    ->where(fn ($q) => $q->whereNull('archived_at')->orWhere('id', $currentAccountId)),
            ],
            'category_id' => [
                'nullable', 'integer',
                Rule::exists('categories', 'id')->where('user_id', $this->user()->id)->where('kind', CategoryKind::Expense->value),
            ],
            'auto_debit' => ['sometimes', 'boolean'],
            'active_from' => ['sometimes', 'date_format:Y-m-d'],
            'active_to' => ['nullable', 'date_format:Y-m-d', 'after_or_equal:active_from'],
        ];
    }

    public function messages(): array
    {
        return [
            'account_id.exists' => 'Choose one of your active accounts.',
            'category_id.exists' => 'Choose one of your expense categories.',
        ];
    }

    /** @return array<string, mixed> */
    public function payload(): array
    {
        return ['category_id' => null, 'auto_debit' => false, 'active_to' => null, ...$this->validated()];
    }
}
