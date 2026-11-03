<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/** @mixin \App\Models\Lending */
class LendingResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        $repayments = $this->relationLoaded('repayments')
            ? $this->repayments
            : $this->repayments()->orderBy('received_on')->orderBy('id')->get();

        return [
            'id' => $this->id,
            'person_name' => $this->person_name,
            'contact' => $this->contact,
            'amount' => $this->amount,
            'repaid' => (int) $repayments->sum('amount'),
            'outstanding' => $this->outstanding(),
            'lent_on' => $this->lent_on->toDateString(),
            'expected_by' => $this->expected_by?->toDateString(),
            'notes' => $this->notes,
            'account_id' => $this->account_id,
            'transaction_id' => $this->transaction_id,
            'status' => $this->status,
            'closed_on' => $this->closed_on?->toDateString(),
            'is_overdue' => $this->isOverdue($request->user()->today()),
            'repayments' => $repayments->map(fn ($r) => [
                'id' => $r->id,
                'amount' => $r->amount,
                'received_on' => $r->received_on->toDateString(),
                'account_id' => $r->account_id,
                'transaction_id' => $r->transaction_id,
            ])->values(),
        ];
    }
}
