<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Milestone 5 automation. Automated is not silent: auto-posted transactions wait for confirmation, every run is
 * logged, and anything undone is remembered so the next run doesn't redo it.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::table('transactions', function (Blueprint $table) {
            // Set when the person confirms an auto-posted transaction (or edits its amount). Null on manual ones.
            $table->timestamp('confirmed_at')->nullable()->after('is_auto');
        });

        Schema::create('job_runs', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->nullable()->constrained()->cascadeOnDelete();
            $table->string('job', 40);
            $table->date('run_for_date');
            $table->string('status', 16); // ok | failed
            $table->json('counts')->nullable();
            $table->text('error')->nullable();
            $table->timestamp('created_at')->nullable();

            $table->index(['user_id', 'run_for_date']);
        });

        // Automation the person undid: never redo it for this source and period.
        Schema::create('automation_opt_outs', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('source_type', 32); // income_source | due
            $table->unsignedBigInteger('source_id');
            $table->char('period', 7);
            $table->timestamp('created_at')->nullable();

            $table->unique(['source_type', 'source_id', 'period']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('automation_opt_outs');
        Schema::dropIfExists('job_runs');
        Schema::table('transactions', fn (Blueprint $table) => $table->dropColumn('confirmed_at'));
    }
};
