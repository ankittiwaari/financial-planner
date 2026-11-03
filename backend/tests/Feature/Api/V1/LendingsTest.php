<?php

use App\Models\Account;
use App\Models\Lending;
use App\Models\Transaction;
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
    $this->account = Account::factory()->for($this->user)->create(['is_primary' => true, 'opening_balance' => 10_000_000]);
});

function lendingInput(array $overrides = []): array
{
    return array_merge([
        'person_name' => 'Ravi',
        'amount' => 2_000_000,
        'lent_on' => '2026-10-01',
        'account_id' => test()->account->id,
    ], $overrides);
}

function balance(): int
{
    return test()->getJson('/api/v1/accounts/'.test()->account->id)->json('data.balance');
}

it('records a lending and books the money leaving the account', function () {
    $response = $this->postJson('/api/v1/lendings', lendingInput(['expected_by' => '2026-12-31', 'notes' => 'Bike repair']))
        ->assertCreated()
        ->assertJsonPath('data.status', 'open')
        ->assertJsonPath('data.repaid', 0)
        ->assertJsonPath('data.outstanding', 2_000_000)
        ->assertJsonPath('data.is_overdue', false)
        ->assertJsonPath('data.repayments', []);

    $transaction = Transaction::query()->sole();
    expect($transaction->direction->value)->toBe('debit')
        ->and($transaction->amount)->toBe(2_000_000)
        ->and($transaction->category_id)->toBeNull()
        ->and($transaction->description)->toBe('Lent to Ravi')
        ->and($transaction->source_type)->toBe('lending')
        ->and($response->json('data.transaction_id'))->toBe($transaction->id)
        ->and(balance())->toBe(8_000_000);
});

it('records an old lending without touching any account', function () {
    $this->postJson('/api/v1/lendings', lendingInput(['account_id' => null]))
        ->assertCreated()
        ->assertJsonPath('data.transaction_id', null);

    expect(Transaction::query()->count())->toBe(0);
});

it('takes repayments until settled, crediting the account', function () {
    $id = $this->postJson('/api/v1/lendings', lendingInput())->json('data.id');

    $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 500_000, 'account_id' => $this->account->id])
        ->assertCreated()
        ->assertJsonPath('data.status', 'partially_repaid')
        ->assertJsonPath('data.repaid', 500_000)
        ->assertJsonPath('data.outstanding', 1_500_000)
        ->assertJsonPath('data.repayments.0.received_on', '2026-10-29');

    $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 1_600_000])
        ->assertJsonValidationErrors(['amount' => '₹15,000']);

    $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 1_500_000, 'received_on' => '2026-10-28'])
        ->assertCreated()
        ->assertJsonPath('data.status', 'settled')
        ->assertJsonPath('data.closed_on', '2026-10-29') // the latest repayment date
        ->assertJsonPath('data.outstanding', 0);

    $credit = Transaction::query()->where('direction', 'credit')->sole();
    expect($credit->category->name)->toBe('Repayment received')
        ->and($credit->description)->toBe('Repayment from Ravi')
        ->and(balance())->toBe(8_500_000); // the second repayment went to no account

    $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 1])->assertStatus(409);
});

it('undoes a repayment and its credit', function () {
    $id = $this->postJson('/api/v1/lendings', lendingInput())->json('data.id');
    $repayment = $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 2_000_000, 'account_id' => $this->account->id])
        ->assertJsonPath('data.status', 'settled')
        ->json('data.repayments.0.id');

    $this->deleteJson("/api/v1/lendings/{$id}/repayments/{$repayment}")
        ->assertOk()
        ->assertJsonPath('data.status', 'open')
        ->assertJsonPath('data.closed_on', null)
        ->assertJsonPath('data.outstanding', 2_000_000);

    expect(balance())->toBe(8_000_000);
});

it('keeps lending and ledger in step when their transactions are deleted', function () {
    $id = $this->postJson('/api/v1/lendings', lendingInput())->json('data.id');
    $lendTx = Lending::find($id)->transaction_id;
    $repayTx = $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 500_000, 'account_id' => $this->account->id])
        ->json('data.repayments.0.transaction_id');

    $this->deleteJson("/api/v1/transactions/{$repayTx}")->assertNoContent();
    $this->getJson("/api/v1/lendings/{$id}")->assertJsonPath('data.repayments', [])->assertJsonPath('data.status', 'open');

    $this->deleteJson("/api/v1/transactions/{$lendTx}")->assertNoContent();
    $this->getJson("/api/v1/lendings/{$id}")
        ->assertJsonPath('data.account_id', null)
        ->assertJsonPath('data.transaction_id', null)
        ->assertJsonPath('data.outstanding', 2_000_000);
});

it('refuses to edit lending and due transactions from the ledger', function () {
    $this->postJson('/api/v1/lendings', lendingInput());
    $transaction = Transaction::query()->sole();

    $this->putJson("/api/v1/transactions/{$transaction->id}", [
        'account_id' => $this->account->id, 'direction' => 'debit', 'amount' => 1, 'occurred_on' => '2026-10-01',
    ])->assertStatus(409)->assertJsonPath('message', 'This transaction is managed from Lent money. Change it there, or delete it here.');
});

it('moves the debit when a lending is edited', function () {
    $other = Account::factory()->for($this->user)->create();
    $id = $this->postJson('/api/v1/lendings', lendingInput())->json('data.id');

    $this->putJson("/api/v1/lendings/{$id}", lendingInput(['amount' => 3_000_000, 'lent_on' => '2026-10-02', 'account_id' => $other->id]))
        ->assertOk()
        ->assertJsonPath('data.amount', 3_000_000);

    $transaction = Transaction::query()->sole();
    expect($transaction->account_id)->toBe($other->id)
        ->and($transaction->amount)->toBe(3_000_000)
        ->and($transaction->occurred_on)->toBe('2026-10-02')
        ->and(balance())->toBe(10_000_000);

    $this->putJson("/api/v1/lendings/{$id}", lendingInput(['account_id' => null]))->assertOk();
    expect(Transaction::query()->count())->toBe(0);
});

it('refuses to lower the amount below what was repaid', function () {
    $id = $this->postJson('/api/v1/lendings', lendingInput())->json('data.id');
    $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 1_500_000])->assertCreated();

    $this->putJson("/api/v1/lendings/{$id}", lendingInput(['amount' => 1_000_000]))
        ->assertJsonValidationErrors(['amount' => '₹15,000 already repaid']);

    $this->putJson("/api/v1/lendings/{$id}", lendingInput(['amount' => 1_500_000]))
        ->assertOk()
        ->assertJsonPath('data.status', 'settled');
});

it('writes off what is left and can reopen it', function () {
    $id = $this->postJson('/api/v1/lendings', lendingInput())->json('data.id');
    $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 500_000])->assertCreated();

    $this->postJson("/api/v1/lendings/{$id}/write-off")
        ->assertOk()
        ->assertJsonPath('data.status', 'written_off')
        ->assertJsonPath('data.closed_on', '2026-10-29')
        ->assertJsonPath('data.outstanding', 0);
    $this->postJson("/api/v1/lendings/{$id}/write-off")->assertStatus(409);

    $this->postJson("/api/v1/lendings/{$id}/reopen")
        ->assertOk()
        ->assertJsonPath('data.status', 'partially_repaid')
        ->assertJsonPath('data.outstanding', 1_500_000);
    $this->postJson("/api/v1/lendings/{$id}/reopen")->assertStatus(409);
});

it('brings a written-off lending back when money arrives', function () {
    $id = $this->postJson('/api/v1/lendings', lendingInput())->json('data.id');
    $this->postJson("/api/v1/lendings/{$id}/write-off")->assertOk();

    $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 2_000_000])
        ->assertCreated()
        ->assertJsonPath('data.status', 'settled');
});

it('deletes a lending with its repayments and their transactions', function () {
    $id = $this->postJson('/api/v1/lendings', lendingInput())->json('data.id');
    $this->postJson("/api/v1/lendings/{$id}/repayments", ['amount' => 500_000, 'account_id' => $this->account->id]);

    $this->deleteJson("/api/v1/lendings/{$id}")->assertNoContent();

    expect(Transaction::query()->count())->toBe(0)
        ->and(balance())->toBe(10_000_000);
});

it('balances each person, grouping names regardless of case and spacing', function () {
    // Ravi: ₹20k and ₹5k, ₹8k back. Priya: ₹10k overdue. Amit: settled. Sam: written off.
    $ravi = $this->postJson('/api/v1/lendings', lendingInput(['person_name' => 'Ravi Kumar', 'expected_by' => '2026-11-30']))->json('data.id');
    $this->postJson('/api/v1/lendings', lendingInput(['person_name' => '  ravi   kumar ', 'amount' => 500_000, 'lent_on' => '2026-10-20', 'contact' => '98xxxxxx01']));
    $this->postJson("/api/v1/lendings/{$ravi}/repayments", ['amount' => 800_000])->assertCreated();
    $this->postJson('/api/v1/lendings', lendingInput(['person_name' => 'Priya', 'amount' => 1_000_000, 'lent_on' => '2026-08-01', 'expected_by' => '2026-09-30']));
    $amit = $this->postJson('/api/v1/lendings', lendingInput(['person_name' => 'Amit', 'amount' => 300_000]))->json('data.id');
    $this->postJson("/api/v1/lendings/{$amit}/repayments", ['amount' => 300_000])->assertCreated();
    $sam = $this->postJson('/api/v1/lendings', lendingInput(['person_name' => 'Sam', 'amount' => 700_000]))->json('data.id');
    $this->postJson("/api/v1/lendings/{$sam}/write-off")->assertOk();

    $summary = $this->getJson('/api/v1/lendings/summary')->assertOk()->json('data');

    expect($summary['total_outstanding'])->toBe(1_700_000 + 1_000_000)
        ->and($summary['overdue_total'])->toBe(1_000_000)
        ->and($summary['people_owing'])->toBe(2)
        ->and(array_column($summary['people'], 'name'))->toBe(['ravi kumar', 'Priya', 'Amit', 'Sam'])
        ->and($summary['people'][0])->toMatchArray([
            'contact' => '98xxxxxx01',
            'lent' => 2_500_000,
            'repaid' => 800_000,
            'written_off' => 0,
            'outstanding' => 1_700_000,
            'open_count' => 2,
            'overdue' => false,
            'next_expected_by' => '2026-11-30',
            'last_lent_on' => '2026-10-20',
        ])
        ->and($summary['people'][1])->toMatchArray(['outstanding' => 1_000_000, 'overdue' => true])
        ->and($summary['people'][2])->toMatchArray(['lent' => 300_000, 'repaid' => 300_000, 'outstanding' => 0, 'open_count' => 0])
        ->and($summary['people'][3])->toMatchArray(['written_off' => 700_000, 'outstanding' => 0]);
});

it('filters lendings by person and status', function () {
    $this->postJson('/api/v1/lendings', lendingInput(['person_name' => 'Ravi Kumar']));
    $settled = $this->postJson('/api/v1/lendings', lendingInput(['person_name' => 'RAVI KUMAR', 'amount' => 100]))->json('data.id');
    $this->postJson("/api/v1/lendings/{$settled}/repayments", ['amount' => 100]);
    $this->postJson('/api/v1/lendings', lendingInput(['person_name' => 'Priya']));

    $this->getJson('/api/v1/lendings?person=ravi%20kumar')->assertJsonCount(2, 'data')->assertJsonPath('data.1.id', $settled);
    $this->getJson('/api/v1/lendings?status=outstanding')->assertJsonCount(2, 'data');
    $this->getJson('/api/v1/lendings?status=settled')->assertJsonCount(1, 'data');
});

it('validates dates and accounts', function () {
    $archived = Account::factory()->for($this->user)->archived()->create();

    $this->postJson('/api/v1/lendings', lendingInput(['expected_by' => '2026-09-01']))->assertJsonValidationErrors(['expected_by']);
    $this->postJson('/api/v1/lendings', lendingInput(['account_id' => $archived->id]))->assertJsonValidationErrors(['account_id']);
});

it("hides other users' lendings and repayments", function () {
    $theirs = Lending::factory()->create();
    $repayment = $theirs->repayments()->create(['amount' => 100, 'received_on' => '2026-10-01']);
    $mine = $this->postJson('/api/v1/lendings', lendingInput())->json('data.id');

    $this->getJson("/api/v1/lendings/{$theirs->id}")->assertNotFound();
    $this->postJson("/api/v1/lendings/{$theirs->id}/repayments", ['amount' => 1])->assertNotFound();
    $this->deleteJson("/api/v1/lendings/{$mine}/repayments/{$repayment->id}")->assertNotFound();
    $this->getJson('/api/v1/lendings/summary')->assertJsonPath('data.people.0.name', 'Ravi')->assertJsonCount(1, 'data.people');
});
