<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/** @mixin \App\Models\CreditCard */
class CreditCardResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'nickname' => $this->nickname,
            'last4' => $this->last4,
            'issuer' => $this->issuer,
            'credit_limit' => $this->credit_limit,
            'statement_day' => $this->statement_day,
            'due_day' => $this->due_day,
            'pay_from_account_id' => $this->pay_from_account_id,
            'estimate_mode' => $this->estimate_mode,
            'fixed_estimate' => $this->fixed_estimate,
            'latest_statement' => $this->latestStatement ? CardStatementResource::make($this->latestStatement) : null,
        ];
    }
}
