<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/** @mixin \App\Models\Transaction */
class TransactionResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'account_id' => $this->account_id,
            'category_id' => $this->category_id,
            'direction' => $this->direction,
            'amount' => $this->amount,
            'occurred_on' => $this->occurred_on,
            'description' => $this->description,
            'source_type' => $this->source_type,
            'source_id' => $this->source_id,
            'period' => $this->period,
            'is_auto' => $this->is_auto,
        ];
    }
}
