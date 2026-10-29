<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/**
 * @mixin \App\Models\IncomeSource
 *
 * @property string|null $credits_max_period loaded with withMax('credits', 'period')
 */
class IncomeSourceResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'name' => $this->name,
            'account_id' => $this->account_id,
            'amount' => $this->amount,
            'is_variable' => $this->is_variable,
            'pay_rule' => $this->pay_rule,
            'pay_day' => $this->pay_day,
            'weekend_shift' => $this->weekend_shift,
            'auto_credit' => $this->auto_credit,
            'active_from' => $this->active_from->toDateString(),
            'active_to' => $this->active_to?->toDateString(),
            'next_pay_date' => $this->nextPayDate($request->user()->today())?->toDateString(),
            'last_credited_period' => $this->credits_max_period ?? $this->credits()->max('period'),
        ];
    }
}
