<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Milestone 7: transactions added offline are sent later, possibly more than once (the first try may have reached
 * the server before the connection dropped). The app tags each with a UUID; a repeat returns the original.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::table('transactions', function (Blueprint $table) {
            $table->uuid('client_ref')->nullable()->after('confirmed_at');
            $table->unique(['user_id', 'client_ref']);
        });
    }

    public function down(): void
    {
        Schema::table('transactions', function (Blueprint $table) {
            $table->dropUnique(['user_id', 'client_ref']);
            $table->dropColumn('client_ref');
        });
    }
};
