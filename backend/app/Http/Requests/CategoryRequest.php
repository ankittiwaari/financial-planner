<?php

namespace App\Http\Requests;

use App\Enums\CategoryKind;
use App\Models\Category;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;
use Illuminate\Validation\Validator;

class CategoryRequest extends FormRequest
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
        $current = $this->route('category');

        return [
            'name' => [
                'required', 'string', 'max:40',
                Rule::unique('categories')
                    ->where('user_id', $this->user()->id)
                    ->where('kind', $this->input('kind'))
                    ->ignore($current instanceof Category ? $current->id : null),
            ],
            'kind' => ['required', Rule::enum(CategoryKind::class)],
            'icon' => ['required', 'string', 'max:16'],
        ];
    }

    public function messages(): array
    {
        return ['name.unique' => 'You already have a category with this name.'];
    }

    /** A used category can't switch between income and expense: its transactions would no longer match. */
    public function after(): array
    {
        return [
            function (Validator $validator) {
                $current = $this->route('category');
                if ($current instanceof Category
                    && $this->input('kind') !== $current->kind->value
                    && $current->transactions()->exists()) {
                    $validator->errors()->add('kind', 'This category is already used, so it must stay '.$current->kind->value.'.');
                }
            },
        ];
    }
}
