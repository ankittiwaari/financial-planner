<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            // Used to work out "today", paydays and due dates in the user's local time. Timestamps stay UTC.
            $table->string('timezone', 64)->default('Asia/Kolkata')->after('password');
            $table->char('currency', 3)->default('INR')->after('timezone');
            // payday: cycle runs from one salary credit to the day before the next. calendar: 1st to month end.
            $table->string('cycle_mode', 16)->default('payday')->after('currency');
        });
    }

    public function down(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn(['timezone', 'currency', 'cycle_mode']);
        });
    }
};
