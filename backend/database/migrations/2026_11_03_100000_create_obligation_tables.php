<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Milestone 2 obligations: loans, credit cards, recurring payments, and the dues they produce.
 * All money columns are integer paise.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('loans', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->foreignId('account_id')->constrained()->restrictOnDelete(); // EMIs are paid from here
            $table->string('name', 60);
            $table->string('lender', 60)->nullable();
            $table->string('type', 16); // App\Enums\LoanType
            $table->unsignedBigInteger('principal');
            $table->decimal('interest_rate', 5, 2); // annual %, reducing balance
            $table->unsignedSmallInteger('tenure_months');
            $table->unsignedBigInteger('emi_amount')->nullable(); // the bank's figure; computed when null
            $table->date('first_emi_date');
            $table->boolean('auto_debit')->default(true); // NACH/ECS
            $table->string('status', 8)->default('active'); // App\Enums\LoanStatus
            $table->date('closed_on')->nullable();
            $table->timestamps();
        });

        Schema::create('credit_cards', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->foreignId('pay_from_account_id')->constrained('accounts')->restrictOnDelete();
            $table->string('nickname', 40);
            $table->char('last4', 4)->nullable(); // never the full number
            $table->string('issuer', 40)->nullable();
            $table->unsignedBigInteger('credit_limit')->nullable();
            $table->unsignedTinyInteger('statement_day');
            $table->unsignedTinyInteger('due_day');
            $table->string('estimate_mode', 16)->default('last_statement'); // App\Enums\EstimateMode
            $table->unsignedBigInteger('fixed_estimate')->nullable();
            $table->timestamps();
        });

        Schema::create('card_statements', function (Blueprint $table) {
            $table->id();
            $table->foreignId('credit_card_id')->constrained()->cascadeOnDelete();
            $table->char('period', 7); // YYYY-MM of the statement date
            $table->date('statement_date');
            $table->date('due_date');
            $table->unsignedBigInteger('total_due');
            $table->unsignedBigInteger('min_due')->nullable();
            $table->unsignedBigInteger('amount_paid')->default(0);
            $table->string('status', 16); // App\Enums\StatementStatus
            $table->timestamps();

            $table->unique(['credit_card_id', 'period']);
        });

        Schema::create('recurring_payments', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->foreignId('account_id')->constrained()->restrictOnDelete();
            $table->foreignId('category_id')->nullable()->constrained()->nullOnDelete();
            $table->string('name', 60);
            $table->unsignedBigInteger('amount');
            $table->string('frequency', 16); // App\Enums\Frequency
            $table->unsignedTinyInteger('day');
            $table->boolean('auto_debit')->default(false);
            $table->date('active_from'); // quarterly/yearly payments fall in this date's month and every 3/12 after
            $table->date('active_to')->nullable();
            $table->timestamps();
        });

        // Every obligation materialises here. Reminders, projections and payments only ever read this table.
        Schema::create('dues', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('source_type', 32); // loan | card_statement | recurring_payment
            $table->unsignedBigInteger('source_id');
            $table->char('period', 7); // YYYY-MM
            $table->string('title', 100);
            $table->unsignedBigInteger('amount');
            $table->date('due_date');
            $table->string('status', 16)->default('upcoming'); // App\Enums\DueStatus
            $table->date('paid_on')->nullable();
            $table->unsignedBigInteger('paid_amount')->nullable();
            $table->foreignId('transaction_id')->nullable()->constrained()->nullOnDelete();
            $table->boolean('auto_generated')->default(true);
            $table->timestamps();

            $table->unique(['source_type', 'source_id', 'period']);
            $table->index(['user_id', 'due_date']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('dues');
        Schema::dropIfExists('recurring_payments');
        Schema::dropIfExists('card_statements');
        Schema::dropIfExists('credit_cards');
        Schema::dropIfExists('loans');
    }
};
