<?php

namespace Database\Seeders;

use App\Models\User;
use App\Support\DefaultCategories;
use Illuminate\Database\Console\Seeds\WithoutModelEvents;
use Illuminate\Database\Seeder;
use RuntimeException;

class DatabaseSeeder extends Seeder
{
    use WithoutModelEvents;

    /**
     * Creates the owner account from OWNER_* in .env. Safe to rerun: it updates the existing owner.
     */
    public function run(): void
    {
        $owner = config('dhanniti.owner');

        if (blank($owner['email']) || blank($owner['password'])) {
            throw new RuntimeException('Set OWNER_EMAIL and OWNER_PASSWORD in .env before seeding.');
        }

        $user = User::updateOrCreate(
            ['email' => strtolower($owner['email'])],
            [
                'name' => $owner['name'],
                'password' => $owner['password'],
                'timezone' => $owner['timezone'],
            ],
        );
        // Model events are off while seeding, so the created hook didn't run.
        DefaultCategories::ensureFor($user);

        $this->command?->info("Owner account ready: {$owner['email']}");
    }
}
