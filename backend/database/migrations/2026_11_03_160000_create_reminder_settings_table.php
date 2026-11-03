<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Milestone 6: when the phone should remind about dues. The server only stores the preference; the phone schedules
 * the notifications itself, so they fire offline.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('reminder_settings', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->unique()->constrained()->cascadeOnDelete();
            $table->boolean('enabled')->default(true);
            $table->json('offsets_days'); // days before the due date, e.g. [5, 2, 0]; 0 = on the day
            $table->char('time_of_day', 5)->default('09:00'); // HH:MM in the user's timezone
            $table->boolean('overdue_daily')->default(true);
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('reminder_settings');
    }
};
