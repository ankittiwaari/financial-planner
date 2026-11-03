<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Milestone 3: money lent to people and what they've paid back. All money columns are integer paise.
 * Outstanding amounts are receivables: shown as "expected back", never counted as spendable.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('lendings', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            // Where the money came from. Null for a loan made before tracking started: no transaction is booked.
            $table->foreignId('account_id')->nullable()->constrained()->restrictOnDelete();
            $table->foreignId('transaction_id')->nullable()->constrained()->nullOnDelete();
            $table->string('person_name', 60);
            $table->string('contact', 60)->nullable();
            $table->unsignedBigInteger('amount');
            $table->date('lent_on');
            $table->date('expected_by')->nullable();
            $table->string('notes', 200)->nullable();
            $table->string('status', 16)->default('open'); // App\Enums\LendingStatus
            $table->date('closed_on')->nullable(); // settled or written off
            $table->timestamps();

            $table->index(['user_id', 'person_name']);
        });

        Schema::create('lending_repayments', function (Blueprint $table) {
            $table->id();
            $table->foreignId('lending_id')->constrained()->cascadeOnDelete();
            // Where the money went. Null when it isn't tracked in an account: no transaction is booked.
            $table->foreignId('account_id')->nullable()->constrained()->restrictOnDelete();
            $table->foreignId('transaction_id')->nullable()->constrained()->nullOnDelete();
            $table->unsignedBigInteger('amount');
            $table->date('received_on');
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('lending_repayments');
        Schema::dropIfExists('lendings');
    }
};
