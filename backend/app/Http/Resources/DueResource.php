<?php

namespace App\Http\Resources;

use App\Models\CardStatement;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/** @mixin \App\Models\Due */
class DueResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        $source = $this->source;

        return [
            'id' => $this->id,
            'source_type' => $this->source_type,
            'source_id' => $this->source_id,
            'period' => $this->period,
            'title' => $this->title,
            'amount' => $this->amount,
            'due_date' => $this->due_date->toDateString(),
            'status' => $this->displayStatus($request->user()->today()),
            'paid_on' => $this->paid_on?->toDateString(),
            'paid_amount' => $this->paid_amount,
            'transaction_id' => $this->transaction_id,
            'account_id' => $this->payFromAccountId(),
            'auto_debit' => (bool) ($source->auto_debit ?? false),
            // Card bills before the real statement is entered.
            'is_estimate' => $source instanceof CardStatement && $source->isEstimate(),
            'min_due' => $source instanceof CardStatement ? $source->min_due : null,
        ];
    }
}
