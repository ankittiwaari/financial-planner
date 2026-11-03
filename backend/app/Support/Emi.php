<?php

namespace App\Support;

/**
 * Reducing-balance loan maths, in paise. Banks quote EMIs in whole rupees, so the computed EMI is rounded to
 * the rupee; the last instalment absorbs the rounding.
 */
final class Emi
{
    /** EMI = P·r·(1+r)^n / ((1+r)^n − 1), r = monthly rate. */
    public static function amount(int $principal, float $annualRate, int $months): int
    {
        if ($annualRate <= 0) {
            return (int) (ceil($principal / $months / 100) * 100);
        }
        $r = $annualRate / 1200;
        $factor = (1 + $r) ** $months;

        return (int) (round($principal * $r * $factor / ($factor - 1) / 100) * 100);
    }

    public static function monthlyInterest(int $outstanding, float $annualRate): int
    {
        return (int) round($outstanding * $annualRate / 1200);
    }

    /**
     * One row per instalment until the loan is repaid or the tenure ends; the final row clears whatever is left.
     *
     * @return list<array{installment: int, emi: int, interest: int, principal: int, outstanding: int}>
     */
    public static function schedule(int $principal, float $annualRate, int $months, int $emi): array
    {
        $rows = [];
        $outstanding = $principal;
        for ($n = 1; $n <= $months && $outstanding > 0; $n++) {
            $interest = self::monthlyInterest($outstanding, $annualRate);
            $repay = $n === $months ? $outstanding : min(max($emi - $interest, 0), $outstanding);
            $outstanding -= $repay;
            $rows[] = [
                'installment' => $n,
                'emi' => $repay + $interest,
                'interest' => $interest,
                'principal' => $repay,
                'outstanding' => $outstanding,
            ];
        }

        return $rows;
    }
}
