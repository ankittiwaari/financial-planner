<?php

use App\Models\Account;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;

uses(RefreshDatabase::class);

beforeEach(function () {
    $this->user = User::factory()->create();
    Sanctum::actingAs($this->user);
    $this->account = Account::factory()->for($this->user)->create(['is_primary' => true]);
    $this->groceries = $this->user->categories()->where('name', 'Groceries')->value('id');
    $this->salary = $this->user->categories()->where('name', 'Salary')->value('id');
});

function spend(array $overrides = []): Illuminate\Testing\TestResponse
{
    return test()->postJson('/api/v1/transactions', array_merge([
        'account_id' => test()->account->id,
        'category_id' => test()->groceries,
        'direction' => 'debit',
        'amount' => 45_050,
        'occurred_on' => '2026-10-28',
        'description' => 'Big Basket',
    ], $overrides));
}

it('records, updates and deletes a transaction', function () {
    $id = spend()
        ->assertCreated()
        ->assertJsonPath('data.amount', 45_050)
        ->assertJsonPath('data.occurred_on', '2026-10-28')
        ->assertJsonPath('data.source_type', null)
        ->assertJsonPath('data.is_auto', false)
        ->json('data.id');

    // PUT replaces: leaving out category_id and description clears them.
    $this->putJson("/api/v1/transactions/{$id}", [
        'account_id' => $this->account->id,
        'direction' => 'debit',
        'amount' => 50_000,
        'occurred_on' => '2026-10-27',
    ])->assertOk()
        ->assertJsonPath('data.amount', 50_000)
        ->assertJsonPath('data.category_id', null)
        ->assertJsonPath('data.description', null);

    $this->getJson("/api/v1/accounts/{$this->account->id}")->assertJsonPath('data.balance', -50_000);

    $this->deleteJson("/api/v1/transactions/{$id}")->assertNoContent();
    $this->getJson("/api/v1/transactions/{$id}")->assertNotFound();
});

it('rejects a category of the wrong kind', function () {
    spend(['category_id' => $this->salary])
        ->assertUnprocessable()
        ->assertJsonValidationErrors(['category_id' => 'income category']);
});

it('rejects archived and foreign accounts and foreign categories', function () {
    $archived = Account::factory()->for($this->user)->archived()->create();
    $foreign = Account::factory()->create();
    $foreignCategory = $foreign->user->categories()->where('name', 'Groceries')->value('id');

    spend(['account_id' => $archived->id])->assertJsonValidationErrors(['account_id' => 'active accounts']);
    spend(['account_id' => $foreign->id])->assertJsonValidationErrors(['account_id']);
    spend(['category_id' => $foreignCategory])->assertJsonValidationErrors(['category_id']);
});

it('validates amounts and dates', function () {
    spend(['amount' => 0])->assertJsonValidationErrors(['amount']);
    spend(['amount' => 12.5])->assertJsonValidationErrors(['amount']);
    spend(['occurred_on' => '28/10/2026'])->assertJsonValidationErrors(['occurred_on']);
    spend(['direction' => 'sideways'])->assertJsonValidationErrors(['direction']);
});

it('lists newest first with filters and a cursor', function () {
    foreach (['2026-10-01', '2026-10-15', '2026-10-15', '2026-10-28'] as $date) {
        spend(['occurred_on' => $date]);
    }
    spend(['direction' => 'credit', 'category_id' => $this->salary, 'occurred_on' => '2026-10-30']);

    $page = $this->getJson('/api/v1/transactions?per_page=2')->assertOk();
    expect(collect($page->json('data'))->pluck('occurred_on')->all())->toBe(['2026-10-30', '2026-10-28'])
        ->and($page->json('meta.next_cursor'))->not->toBeNull();

    $next = $this->getJson('/api/v1/transactions?per_page=2&cursor='.$page->json('meta.next_cursor'))->assertOk();
    $dates = collect($next->json('data'));
    expect($dates->pluck('occurred_on')->all())->toBe(['2026-10-15', '2026-10-15'])
        ->and($dates[0]['id'])->toBeGreaterThan($dates[1]['id']);

    $this->getJson('/api/v1/transactions?direction=credit')->assertJsonCount(1, 'data');
    $this->getJson('/api/v1/transactions?from=2026-10-10&to=2026-10-20')->assertJsonCount(2, 'data');

    $last = $this->getJson('/api/v1/transactions?per_page=100')->assertJsonPath('meta.next_cursor', null);
    expect($last->json('data'))->toHaveCount(5);
});

it('totals a date range', function () {
    spend(['amount' => 30_000, 'occurred_on' => '2026-10-02']);
    spend(['amount' => 20_000, 'occurred_on' => '2026-10-31']);
    spend(['amount' => 99_999, 'occurred_on' => '2026-11-01']); // outside the range
    spend(['direction' => 'credit', 'category_id' => $this->salary, 'amount' => 12_000_000, 'occurred_on' => '2026-10-30']);

    $this->getJson('/api/v1/transactions/summary?from=2026-10-01&to=2026-10-31')
        ->assertOk()
        ->assertExactJson(['data' => [
            'from' => '2026-10-01',
            'to' => '2026-10-31',
            'credit_total' => 12_000_000,
            'debit_total' => 50_000,
            'net' => 11_950_000,
        ]]);

    $this->getJson('/api/v1/transactions/summary?from=2026-10-31&to=2026-10-01')->assertJsonValidationErrors(['to']);
});

it("hides other users' transactions", function () {
    $theirs = Transaction::factory()->create();

    $this->getJson("/api/v1/transactions/{$theirs->id}")->assertNotFound();
    $this->deleteJson("/api/v1/transactions/{$theirs->id}")->assertNotFound();
    $this->getJson('/api/v1/transactions')->assertJsonCount(0, 'data');
});
