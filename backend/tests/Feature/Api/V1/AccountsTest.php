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
});

it('makes the first account primary and computes its balance', function () {
    $response = $this->postJson('/api/v1/accounts', ['name' => 'HDFC Savings', 'type' => 'bank', 'opening_balance' => 1_000_000])
        ->assertCreated()
        ->assertJsonPath('data.is_primary', true)
        ->assertJsonPath('data.balance', 1_000_000);

    $account = Account::find($response->json('data.id'));
    Transaction::factory()->for($account)->create(['user_id' => $this->user->id, 'amount' => 250_000]);
    Transaction::factory()->for($account)->credit()->create(['user_id' => $this->user->id, 'amount' => 5_000_000]);

    $this->getJson("/api/v1/accounts/{$account->id}")
        ->assertOk()
        ->assertJsonPath('data.balance', 1_000_000 - 250_000 + 5_000_000);
});

it('allows a negative opening balance', function () {
    $this->postJson('/api/v1/accounts', ['name' => 'Overdrawn', 'type' => 'bank', 'opening_balance' => -150_050])
        ->assertCreated()
        ->assertJsonPath('data.balance', -150_050);
});

it('moves primary when another account is made primary', function () {
    $first = $this->postJson('/api/v1/accounts', ['name' => 'HDFC', 'type' => 'bank'])->json('data.id');
    $second = $this->postJson('/api/v1/accounts', ['name' => 'Cash', 'type' => 'cash', 'is_primary' => true])->json('data.id');

    expect(Account::find($first)->is_primary)->toBeFalse()
        ->and(Account::find($second)->is_primary)->toBeTrue();

    // Unticking primary on its own never leaves you with no primary account.
    $this->putJson("/api/v1/accounts/{$second}", ['name' => 'Cash', 'type' => 'cash', 'is_primary' => false])
        ->assertJsonPath('data.is_primary', true);
});

it('lists primary first, hides archived accounts, and hands primary on when archiving', function () {
    $hdfc = $this->postJson('/api/v1/accounts', ['name' => 'HDFC', 'type' => 'bank'])->json('data.id');
    $this->postJson('/api/v1/accounts', ['name' => 'Axis', 'type' => 'bank']);

    $this->deleteJson("/api/v1/accounts/{$hdfc}")->assertNoContent();

    $this->getJson('/api/v1/accounts')
        ->assertOk()
        ->assertJsonCount(1, 'data')
        ->assertJsonPath('data.0.name', 'Axis')
        ->assertJsonPath('data.0.is_primary', true);

    $this->getJson('/api/v1/accounts?include_archived=1')->assertJsonCount(2, 'data');
});

it('validates account input', function () {
    $this->postJson('/api/v1/accounts', ['name' => '', 'type' => 'locker'])
        ->assertUnprocessable()
        ->assertJsonValidationErrors(['name', 'type']);
});

it("hides other users' accounts", function () {
    $theirs = Account::factory()->create();

    $this->getJson("/api/v1/accounts/{$theirs->id}")->assertNotFound();
    $this->putJson("/api/v1/accounts/{$theirs->id}", ['name' => 'Mine now', 'type' => 'bank'])->assertNotFound();
    $this->deleteJson("/api/v1/accounts/{$theirs->id}")->assertNotFound();
    $this->getJson('/api/v1/accounts')->assertJsonCount(0, 'data');
});
