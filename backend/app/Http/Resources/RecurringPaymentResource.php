<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/** @mixin \App\Models\RecurringPayment */
class RecurringPaymentResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'name' => $this->name,
            'amount' => $this->amount,
            'frequency' => $this->frequency,
            'day' => $this->day,
            'account_id' => $this->account_id,
            'category_id' => $this->category_id,
            'auto_debit' => $this->auto_debit,
            'active_from' => $this->active_from->toDateString(),
            'active_to' => $this->active_to?->toDateString(),
            'next_date' => $this->nextDate($request->user()->today())?->toDateString(),
        ];
    }
}
