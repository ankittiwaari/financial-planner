<?php

use App\Models\User;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Hash;

uses(RefreshDatabase::class);

it('creates the owner from config and updates it on rerun', function () {
    config()->set('dhanniti.owner', [
        'name' => 'Ankit',
        'email' => 'Owner@DhanNiti.test',
        'password' => 'first-password',
        'timezone' => 'Asia/Kolkata',
    ]);

    $this->seed(DatabaseSeeder::class);
    config()->set('dhanniti.owner.password', 'second-password');
    $this->seed(DatabaseSeeder::class);

    $owner = User::sole();
    expect($owner->email)->toBe('owner@dhanniti.test')
        ->and(Hash::check('second-password', $owner->password))->toBeTrue();
});

it('refuses to seed without owner credentials', function () {
    config()->set('dhanniti.owner.email', null);

    $this->seed(DatabaseSeeder::class);
})->throws(RuntimeException::class, 'OWNER_EMAIL');
