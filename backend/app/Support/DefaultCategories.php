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

    public const LOAN_EMI = 'Loan EMI';

    public const CARD_BILL = 'Credit card bill';

    public const BILLS = 'Bills & utilities';

    public const REPAYMENT = 'Repayment received';

    /** @var array<string, array<string, string>> kind => [name => icon] */
    private const DEFAULTS = [
        'income' => [
            self::SALARY => '💼',
            'Interest' => '📈',
            'Refund' => '↩️',
            self::REPAYMENT => '🤝',
            'Other income' => '💰',
        ],
        'expense' => [
            'Food & dining' => '🍔',
            'Groceries' => '🛒',
            'Rent' => '🏠',
            'Transport' => '🚌',
            'Fuel' => '⛽',
            'Shopping' => '🛍️',
            self::BILLS => '💡',
            self::LOAN_EMI => '🏦',
            self::CARD_BILL => '💳',
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

    /** A default income category by name (one of the constants above), recreated if the user deleted it. */
    public static function incomeFor(User $user, string $name): \App\Models\Category
    {
        return $user->categories()->firstOrCreate(
            ['kind' => CategoryKind::Income, 'name' => $name],
            ['icon' => self::DEFAULTS['income'][$name]],
        );
    }

    /** A default expense category by name (one of the constants above), recreated if the user deleted it. */
    public static function expenseFor(User $user, string $name): \App\Models\Category
    {
        return $user->categories()->firstOrCreate(
            ['kind' => CategoryKind::Expense, 'name' => $name],
            ['icon' => self::DEFAULTS['expense'][$name]],
        );
    }
}
