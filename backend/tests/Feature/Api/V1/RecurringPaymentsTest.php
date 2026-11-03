<?php

use App\Models\Account;
use App\Models\Due;
use App\Models\RecurringPayment;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;

uses(RefreshDatabase::class);

beforeEach(function () {
    // Thursday 29 Oct 2026, 10:00 in Kolkata.
    $this->travelTo(CarbonImmutable::parse('2026-10-29 04:30:00', 'UTC'));
    $this->user = User::factory()->create(['timezone' => 'Asia/Kolkata']);
    Sanctum::actingAs($this->user);
    $this->account = Account::factory()->for($this->user)->create(['is_primary' => true]);
});

function recurringInput(array $overrides = []): array
{
    return array_merge([
        'name' => 'Rent',
        'amount' => 2_500_000,
        'frequency' => 'monthly',
        'day' => 1,
        'account_id' => test()->account->id,
    ], $overrides);
}

it('creates a monthly payment starting today with its dues', function () {
    $this->postJson('/api/v1/recurring-payments', recurringInput())
        ->assertCreated()
        ->assertJsonPath('data.active_from', '2026-10-29')
        ->assertJsonPath('data.auto_debit', false)
        ->assertJsonPath('data.next_date', '2026-11-01');

    expect(Due::query()->orderBy('due_date')->pluck('due_date')->map->toDateString()->all())
        ->toBe(['2026-11-01', '2026-12-01']);
});

it('schedules quarterly and yearly payments from their start month', function (array $input, ?string $next, int $duesInWindow) {
    $this->postJson('/api/v1/recurring-payments', recurringInput($input))
        ->assertCreated()
        ->assertJsonPath('data.next_date', $next);

    expect(Due::query()->count())->toBe($duesInWindow);
})->with([
    'quarterly, due next month' => [['frequency' => 'quarterly', 'day' => 15, 'active_from' => '2026-08-01'], '2026-11-15', 1],
    'quarterly, not this quarter' => [['frequency' => 'quarterly', 'day' => 15, 'active_from' => '2026-09-01'], '2026-12-15', 0],
    'yearly' => [['frequency' => 'yearly', 'day' => 10, 'active_from' => '2026-03-01'], '2027-03-10', 0],
    'ended' => [['active_from' => '2026-01-01', 'active_to' => '2026-10-31'], null, 0],
    'day 31 in November' => [['day' => 31], '2026-10-31', 2], // 31 Oct, 30 Nov
]);

it('files payments under its category', function () {
    $rentCategory = $this->user->categories()->where('name', 'Rent')->value('id');
    $this->postJson('/api/v1/recurring-payments', recurringInput(['category_id' => $rentCategory]))->assertCreated();
    $due = Due::query()->orderBy('due_date')->first();

    $this->postJson("/api/v1/dues/{$due->id}/pay")->assertOk();

    expect($due->refresh()->transaction->category_id)->toBe($rentCategory);
});

it('only accepts expense categories', function () {
    $salary = $this->user->categories()->where('name', 'Salary')->value('id');

    $this->postJson('/api/v1/recurring-payments', recurringInput(['category_id' => $salary]))
        ->assertJsonValidationErrors(['category_id']);
});

it('drops unpaid dues when deleted', function () {
    $id = $this->postJson('/api/v1/recurring-payments', recurringInput())->json('data.id');

    $this->deleteJson("/api/v1/recurring-payments/{$id}")->assertNoContent();

    expect(Due::query()->count())->toBe(0);
});

it("hides other users' recurring payments", function () {
    $theirs = RecurringPayment::factory()->create();

    $this->getJson("/api/v1/recurring-payments/{$theirs->id}")->assertNotFound();
});
