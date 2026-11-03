<?php

namespace App\Models;

use App\Enums\DueStatus;
use App\Enums\LoanStatus;
use App\Enums\LoanType;
use App\Support\Emi;
use App\Support\Months;
use Carbon\CarbonImmutable;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * A loan repaid in monthly EMIs. Each instalment becomes a due with period = the month it falls in.
 *
 * @property CarbonImmutable $first_emi_date
 * @property CarbonImmutable|null $closed_on
 */
class Loan extends Model
{
    /** @use HasFactory<\Database\Factories\LoanFactory> */
    use HasFactory;

    protected $fillable = [
        'account_id', 'name', 'lender', 'type', 'principal', 'interest_rate', 'tenure_months', 'emi_amount',
        'first_emi_date', 'auto_debit',
    ];

    protected $attributes = ['status' => 'active', 'auto_debit' => true];

    protected function casts(): array
    {
        return [
            'type' => LoanType::class,
            'principal' => 'integer',
            'interest_rate' => 'float',
            'tenure_months' => 'integer',
            'emi_amount' => 'integer',
            'first_emi_date' => 'immutable_date',
            'auto_debit' => 'boolean',
            'status' => LoanStatus::class,
            'closed_on' => 'immutable_date',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function account(): BelongsTo
    {
        return $this->belongsTo(Account::class);
    }

    public function dues(): HasMany
    {
        return $this->hasMany(Due::class, 'source_id')->where('source_type', Due::SOURCE_LOAN);
    }

    public function scopeActive(Builder $query): void
    {
        $query->where('status', LoanStatus::Active);
    }

    /** The bank's EMI if given, otherwise the computed one. */
    public function emi(): int
    {
        return $this->emi_amount ?? Emi::amount($this->principal, $this->interest_rate, $this->tenure_months);
    }

    /** Instalment $n (1-based) falls on the same day of the month as the first, clamped for short months. */
    public function installmentDate(int $n): CarbonImmutable
    {
        return $this->first_emi_date->addMonthsNoOverflow($n - 1);
    }

    /** @return list<array{installment: int, due_date: CarbonImmutable, emi: int, interest: int, principal: int, outstanding: int}> */
    public function schedule(): array
    {
        return array_map(
            fn (array $row) => ['due_date' => $this->installmentDate($row['installment']), ...$row],
            Emi::schedule($this->principal, $this->interest_rate, $this->tenure_months, $this->emi()),
        );
    }

    /**
     * How far repayment has got. Instalments dated before today count as paid (the loan was usually being repaid
     * before it was added here), plus any later ones already marked paid in dues.
     *
     * @return array{paid: int, left: int, outstanding: int, next: array<string, mixed>|null, schedule: list<array<string, mixed>>}
     */
    public function progress(CarbonImmutable $today): array
    {
        $paidPeriods = $this->dues()->where('status', DueStatus::Paid)->pluck('period')->all();
        $schedule = $this->schedule();
        $closed = $this->status === LoanStatus::Closed;
        $paid = 0;
        $outstanding = $this->principal;
        $next = null;
        foreach ($schedule as $i => $row) {
            $done = $row['due_date']->lt($today) || in_array(Months::period($row['due_date']), $paidPeriods, true);
            $schedule[$i]['paid'] = $done;
            if ($done) {
                $paid++;
                $outstanding = $row['outstanding'];
            } else {
                $next ??= $row;
            }
        }

        return [
            'paid' => $paid,
            'left' => $closed ? 0 : count($schedule) - $paid,
            'outstanding' => $closed ? 0 : $outstanding,
            'next' => $closed ? null : $next,
            'schedule' => $schedule,
        ];
    }
}
