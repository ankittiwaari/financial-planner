<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/** @mixin \App\Models\Loan */
class LoanResource extends JsonResource
{
    /**
     * @return array<string, mixed>
     */
    public function toArray(Request $request): array
    {
        $progress = $this->progress($request->user()->today());
        $schedule = $progress['schedule'];

        return [
            'id' => $this->id,
            'name' => $this->name,
            'lender' => $this->lender,
            'type' => $this->type,
            'principal' => $this->principal,
            'interest_rate' => $this->interest_rate,
            'tenure_months' => $this->tenure_months,
            'emi_amount' => $this->emi_amount,
            'emi' => $this->emi(),
            'first_emi_date' => $this->first_emi_date->toDateString(),
            'last_emi_date' => $schedule ? end($schedule)['due_date']->toDateString() : null,
            'account_id' => $this->account_id,
            'auto_debit' => $this->auto_debit,
            'status' => $this->status,
            'closed_on' => $this->closed_on?->toDateString(),
            'outstanding' => $progress['outstanding'],
            'emis_paid' => $progress['paid'],
            'emis_left' => $progress['left'],
            'next_emi_date' => $progress['next'] ? $progress['next']['due_date']->toDateString() : null,
            'total_interest' => array_sum(array_column($schedule, 'interest')),
        ];
    }
}
