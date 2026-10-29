<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Milestone 1 ledger. All money columns are integer paise.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('accounts', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('name', 60);
            $table->string('type', 16); // App\Enums\AccountType
            $table->bigInteger('opening_balance')->default(0); // may be negative (overdrawn)
            $table->boolean('is_primary')->default(false);
            $table->timestamp('archived_at')->nullable();
            $table->timestamps();
        });

        Schema::create('categories', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('name', 40);
            $table->string('kind', 16); // App\Enums\CategoryKind
            $table->string('icon', 16);
            $table->timestamp('archived_at')->nullable();
            $table->timestamps();

            $table->unique(['user_id', 'kind', 'name']);
        });

        Schema::create('income_sources', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->foreignId('account_id')->constrained()->restrictOnDelete();
            $table->string('name', 60);
            $table->unsignedBigInteger('amount');
            $table->boolean('is_variable')->default(false);
            $table->string('pay_rule', 24); // App\Enums\PayRule
            $table->unsignedTinyInteger('pay_day')->nullable();
            $table->string('weekend_shift', 8)->default('before'); // App\Enums\WeekendShift
            $table->boolean('auto_credit')->default(true);
            $table->date('active_from');
            $table->date('active_to')->nullable();
            $table->timestamps();
        });

        Schema::create('transactions', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->foreignId('account_id')->constrained()->restrictOnDelete();
            $table->foreignId('category_id')->nullable()->constrained()->nullOnDelete();
            $table->string('direction', 8); // App\Enums\Direction
            $table->unsignedBigInteger('amount');
            $table->date('occurred_on');
            $table->string('description', 200)->nullable();
            // What generated this row, if anything. One row per source per period keeps automation idempotent.
            $table->string('source_type', 32)->nullable();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->char('period', 7)->nullable(); // YYYY-MM
            $table->boolean('is_auto')->default(false);
            $table->timestamps();

            $table->index(['user_id', 'occurred_on', 'id']);
            $table->index(['account_id', 'direction']);
            $table->unique(['source_type', 'source_id', 'period']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('transactions');
        Schema::dropIfExists('income_sources');
        Schema::dropIfExists('categories');
        Schema::dropIfExists('accounts');
    }
};
