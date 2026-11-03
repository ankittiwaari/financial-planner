<?php

use App\Support\Emi;

it('matches published EMIs, rounded to the rupee', function (int $principal, float $rate, int $months, int $expected) {
    expect(Emi::amount($principal, $rate, $months))->toBe($expected);
})->with([
    '₹10 lakh, 12%, 1 year' => [100_000_000, 12.0, 12, 8_884_900], // ₹88,848.79
    '₹50 lakh, 8.5%, 20 years' => [500_000_000, 8.5, 240, 4_339_100], // ₹43,391.16
    '₹5 lakh, 10.5%, 5 years' => [50_000_000, 10.5, 60, 1_074_700], // ₹10,746.94
    'interest-free, rounded up' => [10_000_000, 0.0, 3, 3_333_400],
]);

it('builds a schedule that ends at zero, the last EMI absorbing the rounding', function () {
    $rows = Emi::schedule(100_000_000, 12.0, 12, Emi::amount(100_000_000, 12.0, 12));

    expect($rows)->toHaveCount(12)
        ->and($rows[0])->toBe(['installment' => 1, 'emi' => 8_884_900, 'interest' => 1_000_000, 'principal' => 7_884_900, 'outstanding' => 92_115_100])
        ->and(end($rows)['outstanding'])->toBe(0)
        ->and(array_sum(array_column($rows, 'principal')))->toBe(100_000_000)
        ->and(abs(end($rows)['emi'] - 8_884_900))->toBeLessThan(1_000); // rupee rounding adds up to a few rupees
});

it('stops early when the given EMI repays the loan before the tenure ends', function () {
    $rows = Emi::schedule(10_000_000, 0.0, 12, 5_000_000);

    expect($rows)->toHaveCount(2)->and(end($rows)['outstanding'])->toBe(0);
});
