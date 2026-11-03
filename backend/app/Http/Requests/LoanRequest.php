<?php

namespace App\Http\Requests;

use App\Enums\LoanType;
use App\Models\Loan;
use App\Support\Emi;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;
use Illuminate\Validation\Validator;

class LoanRequest extends FormRequest
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
        $current = $this->route('loan');
        $currentAccountId = $current instanceof Loan ? $current->account_id : null;

        return [
            'name' => ['required', 'string', 'max:60'],
            'lender' => ['nullable', 'string', 'max:60'],
            'type' => ['required', Rule::enum(LoanType::class)],
            'principal' => ['required', 'integer', 'min:1', 'max:100000000000'],
            'interest_rate' => ['required', 'numeric', 'between:0,99.99'],
            'tenure_months' => ['required', 'integer', 'between:1,600'],
            'emi_amount' => ['nullable', 'integer', 'min:1', 'max:100000000000'],
            'first_emi_date' => ['required', 'date_format:Y-m-d'],
            'account_id' => [
                'required', 'integer',
                Rule::exists('accounts', 'id')
                    ->where('user_id', $this->user()->id)
                    ->where(fn ($q) => $q->whereNull('archived_at')->orWhere('id', $currentAccountId)),
            ],
            'auto_debit' => ['sometimes', 'boolean'],
        ];
    }

    public function messages(): array
    {
        return ['account_id.exists' => 'Choose one of your active accounts.'];
    }

    public function after(): array
    {
        return [
            function (Validator $validator) {
                if ($validator->errors()->hasAny(['principal', 'interest_rate', 'emi_amount']) || ! $this->filled('emi_amount')) {
                    return;
                }
                // An EMI at or below the first month's interest never pays the loan down.
                $interest = Emi::monthlyInterest($this->integer('principal'), (float) $this->input('interest_rate'));
                if ($this->integer('emi_amount') <= $interest) {
                    $rupees = number_format($interest / 100, 2);
                    $validator->errors()->add('emi_amount', "The EMI must be more than the first month's interest (₹{$rupees}).");
                }
            },
        ];
    }

    /** @return array<string, mixed> */
    public function payload(): array
    {
        return ['lender' => null, 'emi_amount' => null, 'auto_debit' => true, ...$this->validated()];
    }
}
