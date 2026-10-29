<?php

namespace App\Http\Requests;

use App\Enums\Direction;
use App\Models\Category;
use App\Models\Transaction;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;
use Illuminate\Validation\Validator;

class TransactionRequest extends FormRequest
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
        $current = $this->route('transaction');
        $currentAccountId = $current instanceof Transaction ? $current->account_id : null;

        return [
            'account_id' => [
                'required', 'integer',
                // Active accounts only, though an edit may keep a transaction on its (now archived) account.
                Rule::exists('accounts', 'id')
                    ->where('user_id', $this->user()->id)
                    ->where(fn ($q) => $q->whereNull('archived_at')->orWhere('id', $currentAccountId)),
            ],
            'category_id' => [
                'nullable', 'integer',
                Rule::exists('categories', 'id')->where('user_id', $this->user()->id),
            ],
            'direction' => ['required', Rule::enum(Direction::class)],
            'amount' => ['required', 'integer', 'min:1', 'max:100000000000'],
            'occurred_on' => ['required', 'date_format:Y-m-d'],
            'description' => ['nullable', 'string', 'max:200'],
        ];
    }

    public function messages(): array
    {
        return [
            'account_id.exists' => 'Choose one of your active accounts.',
            'category_id.exists' => 'Choose one of your categories.',
        ];
    }

    public function after(): array
    {
        return [
            function (Validator $validator) {
                $direction = Direction::tryFrom((string) $this->input('direction'));
                $category = $this->filled('category_id') ? Category::find($this->integer('category_id')) : null;
                if ($direction && $category && (int) $category->user_id === $this->user()->id
                    && $category->kind !== $direction->categoryKind()) {
                    $validator->errors()->add('category_id', "“{$category->name}” is an {$category->kind->value} category.");
                }
            },
        ];
    }

    public function attributes(): array
    {
        return ['account_id' => 'account', 'category_id' => 'category', 'occurred_on' => 'date'];
    }

    /**
     * Optional fields absent from the body are cleared, matching PUT semantics.
     *
     * @return array<string, mixed>
     */
    public function payload(): array
    {
        return [
            'category_id' => null,
            'description' => null,
            ...$this->validated(),
        ];
    }
}
