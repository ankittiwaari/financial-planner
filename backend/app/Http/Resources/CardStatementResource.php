<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/** @mixin \App\Models\CardStatement */
class CardStatementResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'credit_card_id' => $this->credit_card_id,
            'period' => $this->period,
            'statement_date' => $this->statement_date->toDateString(),
            'due_date' => $this->due_date->toDateString(),
            'total_due' => $this->total_due,
            'min_due' => $this->min_due,
            'amount_paid' => $this->amount_paid,
            'status' => $this->status,
        ];
    }
}
