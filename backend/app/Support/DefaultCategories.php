<?php

namespace App\Support;

use App\Enums\CategoryKind;
use App\Models\User;

/**
 * The starter set of categories every user gets. Safe to run repeatedly.
 */
final class DefaultCategories
{
    public const SALARY = 'Salary';

    /** @var array<string, array<string, string>> kind => [name => icon] */
    private const DEFAULTS = [
        'income' => [
            self::SALARY => '💼',
            'Interest' => '📈',
            'Refund' => '↩️',
            'Repayment received' => '🤝',
            'Other income' => '💰',
        ],
        'expense' => [
            'Food & dining' => '🍔',
            'Groceries' => '🛒',
            'Rent' => '🏠',
            'Transport' => '🚌',
            'Fuel' => '⛽',
            'Shopping' => '🛍️',
            'Bills & utilities' => '💡',
            'Mobile & internet' => '📱',
            'Health' => '💊',
            'Entertainment' => '🎬',
            'Travel' => '✈️',
            'Education' => '🎓',
            'Gifts' => '🎁',
            'Other' => '🧾',
        ],
    ];

    public static function ensureFor(User $user): void
    {
        foreach (self::DEFAULTS as $kind => $categories) {
            foreach ($categories as $name => $icon) {
                $user->categories()->firstOrCreate(['kind' => $kind, 'name' => $name], ['icon' => $icon]);
            }
        }
    }

    /** The category income credits are filed under, recreated if the user deleted it. */
    public static function salaryFor(User $user): \App\Models\Category
    {
        return $user->categories()->firstOrCreate(
            ['kind' => CategoryKind::Income, 'name' => self::SALARY],
            ['icon' => self::DEFAULTS['income'][self::SALARY]],
        );
    }
}
