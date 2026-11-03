<?php

use App\Models\Account;
use App\Models\CreditCard;
use App\Models\Due;
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

function cardInput(array $overrides = []): array
{
    return array_merge([
        'nickname' => 'ICICI Amazon Pay',
        'last4' => '4321',
        'statement_day' => 28,
        'due_day' => 17,
        'pay_from_account_id' => test()->account->id,
    ], $overrides);
}

it('creates a card with an estimated bill for the statement due in the window', function () {
    $this->postJson('/api/v1/credit-cards', cardInput())
        ->assertCreated()
        ->assertJsonPath('data.estimate_mode', 'last_statement')
        // Statement 28 Oct, due 17 Nov. The 28 Sep statement fell due on 17 Oct, before today.
        ->assertJsonPath('data.latest_statement.period', '2026-10')
        ->assertJsonPath('data.latest_statement.statement_date', '2026-10-28')
        ->assertJsonPath('data.latest_statement.due_date', '2026-11-17')
        ->assertJsonPath('data.latest_statement.status', 'estimated')
        ->assertJsonPath('data.latest_statement.total_due', 0);

    $this->getJson('/api/v1/dues')
        ->assertJsonCount(1, 'data')
        ->assertJsonPath('data.0.title', 'ICICI Amazon Pay ••4321 bill')
        ->assertJsonPath('data.0.due_date', '2026-11-17')
        ->assertJsonPath('data.0.is_estimate', true)
        ->assertJsonPath('data.0.account_id', $this->account->id);
});

it('puts the due date in the same month when it comes after the statement day', function () {
    $this->postJson('/api/v1/credit-cards', cardInput(['statement_day' => 1, 'due_day' => 20]))
        ->assertCreated()
        ->assertJsonPath('data.latest_statement.statement_date', '2026-11-01')
        ->assertJsonPath('data.latest_statement.due_date', '2026-11-20');
});

it('replaces the estimate when the real bill is entered', function () {
    $card = $this->postJson('/api/v1/credit-cards', cardInput())->json('data.id');

    $this->postJson("/api/v1/credit-cards/{$card}/statements", ['period' => '2026-10', 'total_due' => 1_895_000, 'min_due' => 95_000])
        ->assertOk() // the estimate already existed
        ->assertJsonPath('data.status', 'entered')
        ->assertJsonPath('data.total_due', 1_895_000);

    $this->getJson('/api/v1/dues')
        ->assertJsonPath('data.0.amount', 1_895_000)
        ->assertJsonPath('data.0.min_due', 95_000)
        ->assertJsonPath('data.0.is_estimate', false);
});

it('estimates from past bills', function (string $mode, int $expected) {
    $card = CreditCard::factory()->for($this->user)->create(['pay_from_account_id' => $this->account->id, 'estimate_mode' => $mode]);
    foreach (['2026-06' => 900_000, '2026-07' => 1_200_000, '2026-08' => 1_500_000] as $period => $total) {
        $this->postJson("/api/v1/credit-cards/{$card->id}/statements", ['period' => $period, 'total_due' => $total])->assertCreated();
    }

    // The entered bills are unpaid, so they show as overdue too; the estimate is the October one.
    $dues = collect($this->getJson('/api/v1/dues')->json('data'));
    expect($dues->firstWhere('period', '2026-10'))->toMatchArray(['amount' => $expected, 'is_estimate' => true])
        ->and($dues->where('status', 'overdue'))->toHaveCount(3);
})->with([
    'last statement' => ['last_statement', 1_500_000],
    'average of 3' => ['avg_3', 1_200_000],
]);

it('uses a fixed estimate when asked, and requires its amount', function () {
    $this->postJson('/api/v1/credit-cards', cardInput(['estimate_mode' => 'fixed']))
        ->assertJsonValidationErrors(['fixed_estimate']);

    $this->postJson('/api/v1/credit-cards', cardInput(['estimate_mode' => 'fixed', 'fixed_estimate' => 2_000_000]))
        ->assertCreated()
        ->assertJsonPath('data.latest_statement.total_due', 2_000_000);
});

it('updates the statement as the bill is paid in parts', function () {
    $card = $this->postJson('/api/v1/credit-cards', cardInput())->json('data.id');
    $this->postJson("/api/v1/credit-cards/{$card}/statements", ['period' => '2026-10', 'total_due' => 1_000_000])->assertOk();
    $due = Due::query()->sole();

    $this->postJson("/api/v1/dues/{$due->id}/pay", ['amount' => 400_000])->assertOk();
    $this->getJson("/api/v1/credit-cards/{$card}")
        ->assertJsonPath('data.latest_statement.amount_paid', 400_000)
        ->assertJsonPath('data.latest_statement.status', 'partially_paid');

    $this->postJson("/api/v1/dues/{$due->id}/reopen")->assertOk();
    $this->postJson("/api/v1/dues/{$due->id}/pay")->assertOk();
    $this->getJson("/api/v1/credit-cards/{$card}")
        ->assertJsonPath('data.latest_statement.amount_paid', 1_000_000)
        ->assertJsonPath('data.latest_statement.status', 'paid');
});

it('moves open bills when the due day changes', function () {
    $card = $this->postJson('/api/v1/credit-cards', cardInput())->json('data.id');

    $this->putJson("/api/v1/credit-cards/{$card}", cardInput(['due_day' => 20]))->assertOk();

    $this->getJson('/api/v1/dues')->assertJsonCount(1, 'data')->assertJsonPath('data.0.due_date', '2026-11-20');
});

it('validates last 4 digits and refuses full card numbers', function () {
    $this->postJson('/api/v1/credit-cards', cardInput(['last4' => '4111111111111111']))
        ->assertJsonValidationErrors(['last4' => 'last 4 digits']);
});

it("hides other users' cards", function () {
    $theirs = CreditCard::factory()->create();

    $this->getJson("/api/v1/credit-cards/{$theirs->id}")->assertNotFound();
    $this->postJson("/api/v1/credit-cards/{$theirs->id}/statements", ['period' => '2026-10', 'total_due' => 1])->assertNotFound();
});
