<?php

namespace App\Http\Requests;

use App\Enums\PayRule;
use App\Enums\WeekendShift;
use App\Models\IncomeSource;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;

class IncomeSourceRequest extends FormRequest
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
        $current = $this->route('income_source');
        $currentAccountId = $current instanceof IncomeSource ? $current->account_id : null;

        return [
            'name' => ['required', 'string', 'max:60'],
            'account_id' => [
                'required', 'integer',
                Rule::exists('accounts', 'id')
                    ->where('user_id', $this->user()->id)
                    ->where(fn ($q) => $q->whereNull('archived_at')->orWhere('id', $currentAccountId)),
            ],
            'amount' => ['required', 'integer', 'min:1', 'max:100000000000'],
            'is_variable' => ['sometimes', 'boolean'],
            'pay_rule' => ['required', Rule::enum(PayRule::class)],
            'pay_day' => ['nullable', 'integer', 'between:1,31', 'required_if:pay_rule,fixed_day'],
            'weekend_shift' => ['sometimes', Rule::enum(WeekendShift::class)],
            'auto_credit' => ['sometimes', 'boolean'],
            'active_from' => ['sometimes', 'date_format:Y-m-d'],
            'active_to' => ['nullable', 'date_format:Y-m-d', 'after_or_equal:active_from'],
        ];
    }

    public function messages(): array
    {
        return [
            'account_id.exists' => 'Choose one of your active accounts.',
            'pay_day.required_if' => 'Pick the day of the month you get paid.',
        ];
    }

    /** @return array<string, mixed> */
    public function payload(): array
    {
        $data = [
            'is_variable' => false,
            'weekend_shift' => WeekendShift::Before->value,
            'auto_credit' => true,
            'active_to' => null,
            ...$this->validated(),
        ];
        // pay_day only means something for fixed_day.
        if ($data['pay_rule'] !== PayRule::FixedDay->value) {
            $data['pay_day'] = null;
        }

        return $data;
    }
}
