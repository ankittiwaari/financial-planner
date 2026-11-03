<?php

use App\Models\Account;
use App\Models\IncomeSource;
use App\Models\Transaction;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;

uses(RefreshDatabase::class);

beforeEach(function () {
    $this->travelTo(CarbonImmutable::parse('2026-11-03 10:00:00', 'Asia/Kolkata'));
    $this->user = User::factory()->create(['timezone' => 'Asia/Kolkata']);
    Sanctum::actingAs($this->user);
    $this->account = Account::factory()->for($this->user)->create(['is_primary' => true]);
});

it('returns everything the app shows in one snapshot, shaped like the individual endpoints', function () {
    IncomeSource::factory()->for($this->user)->for($this->account)->create();
    $this->postJson('/api/v1/recurring-payments', [
        'name' => 'Rent', 'amount' => 2_500_000, 'frequency' => 'monthly', 'day' => 10, 'account_id' => $this->account->id,
    ])->assertCreated();
    $this->postJson('/api/v1/lendings', ['person_name' => 'Ravi', 'amount' => 100_000, 'lent_on' => '2026-11-01'])->assertCreated();
    foreach (range(1, 55) as $i) {
        Transaction::factory()->for($this->account)->create(['user_id' => $this->user->id, 'occurred_on' => '2026-11-01']);
    }

    $data = $this->getJson('/api/v1/sync')->assertOk()->json('data');

    expect(array_keys($data))->toBe([
        'server_time', 'user', 'accounts', 'categories', 'income_sources', 'transactions', 'loans', 'credit_cards',
        'recurring_payments', 'dues', 'lendings', 'lending_summary', 'reminder_settings', 'dashboard',
    ])
        ->and($data['user']['email'])->toBe($this->user->email)
        ->and($data['accounts'])->toBe($this->getJson('/api/v1/accounts')->json('data'))
        ->and($data['dues'])->toBe($this->getJson('/api/v1/dues')->json('data'))
        ->and($data['dashboard']['cycle'])->toBe($this->getJson('/api/v1/dashboard')->json('data.cycle'))
        ->and($data['transactions']['data'])->toHaveCount(50)
        ->and($data['transactions']['meta']['next_cursor'])->not->toBeNull()
        ->and($data['lending_summary']['total_outstanding'])->toBe(100_000)
        ->and($data['reminder_settings']['offsets_days'])->toBe([5, 2, 0])
        ->and(collect($data['dues'])->pluck('title')->all())->toBe(['Rent', 'Rent']);
});

it('only shows the signed-in user’s data', function () {
    Account::factory()->create(['name' => 'Someone else’s']);

    expect(collect($this->getJson('/api/v1/sync')->json('data.accounts'))->pluck('id')->all())->toBe([$this->account->id]);
});

it('creates a transaction once however often an offline entry is resent', function () {
    $entry = [
        'account_id' => $this->account->id, 'direction' => 'debit', 'amount' => 45_000, 'occurred_on' => '2026-11-02',
        'client_ref' => '8f14e45f-ceea-4b1b-9a6e-1d1d4f3c2a01',
    ];

    $first = $this->postJson('/api/v1/transactions', $entry)->assertCreated()->json('data.id');
    $this->postJson('/api/v1/transactions', $entry)->assertOk()->assertJsonPath('data.id', $first);
    $this->postJson('/api/v1/transactions', [...$entry, 'client_ref' => 'not-a-uuid'])->assertJsonValidationErrors(['client_ref']);

    expect(Transaction::query()->count())->toBe(1);
});
