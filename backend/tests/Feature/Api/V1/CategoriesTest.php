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

it('gives new users a default set of categories', function () {
    $response = $this->getJson('/api/v1/categories')->assertOk();

    $names = collect($response->json('data'))->pluck('name');
    expect($names)->toContain('Salary', 'Groceries', 'Rent', 'Food & dining')
        ->and($response->json('data.0'))->toHaveKeys(['id', 'name', 'kind', 'icon', 'archived']);

    $this->getJson('/api/v1/categories?kind=income')
        ->assertOk()
        ->assertJsonMissing(['kind' => 'expense']);
});

it('creates a category and rejects a duplicate name of the same kind', function () {
    $this->postJson('/api/v1/categories', ['name' => 'Pets', 'kind' => 'expense', 'icon' => '🐶'])
        ->assertCreated()
        ->assertJsonPath('data.name', 'Pets');

    $this->postJson('/api/v1/categories', ['name' => 'Pets', 'kind' => 'expense', 'icon' => '🐱'])
        ->assertUnprocessable()
        ->assertJsonValidationErrors(['name' => 'already have a category']);

    // Same name is fine as the other kind.
    $this->postJson('/api/v1/categories', ['name' => 'Pets', 'kind' => 'income', 'icon' => '🐶'])->assertCreated();
});

it('archives a category so it leaves the picker list', function () {
    $id = $this->user->categories()->where('name', 'Rent')->value('id');

    $this->deleteJson("/api/v1/categories/{$id}")->assertNoContent();

    expect(collect($this->getJson('/api/v1/categories')->json('data'))->pluck('id'))->not->toContain($id);
});

it('stops a used category from switching kind', function () {
    $category = $this->user->categories()->where('name', 'Groceries')->first();
    $account = Account::factory()->for($this->user)->create();
    Transaction::factory()->for($account)->create(['user_id' => $this->user->id, 'category_id' => $category->id]);

    $this->putJson("/api/v1/categories/{$category->id}", ['name' => 'Groceries', 'kind' => 'income', 'icon' => '🛒'])
        ->assertUnprocessable()
        ->assertJsonValidationErrors(['kind']);

    $this->putJson("/api/v1/categories/{$category->id}", ['name' => 'Kirana', 'kind' => 'expense', 'icon' => '🛒'])
        ->assertOk()
        ->assertJsonPath('data.name', 'Kirana');
});
