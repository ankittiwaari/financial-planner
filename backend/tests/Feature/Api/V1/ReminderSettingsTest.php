<?php

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;

uses(RefreshDatabase::class);

beforeEach(function () {
    $this->user = User::factory()->create();
    Sanctum::actingAs($this->user);
});

function reminderInput(array $overrides = []): array
{
    return array_merge(['enabled' => true, 'offsets_days' => [0, 3], 'time_of_day' => '08:30', 'overdue_daily' => false], $overrides);
}

it('starts with T-5, T-2 and on the day at 9am', function () {
    $this->getJson('/api/v1/settings/reminders')
        ->assertOk()
        ->assertExactJson(['data' => ['enabled' => true, 'offsets_days' => [5, 2, 0], 'time_of_day' => '09:00', 'overdue_daily' => true]]);
});

it('saves settings, furthest reminder first', function () {
    $this->putJson('/api/v1/settings/reminders', reminderInput())
        ->assertOk()
        ->assertExactJson(['data' => ['enabled' => true, 'offsets_days' => [3, 0], 'time_of_day' => '08:30', 'overdue_daily' => false]]);

    $this->getJson('/api/v1/settings/reminders')->assertJsonPath('data.offsets_days', [3, 0]);
});

it('allows no reminder days, for overdue nudges only', function () {
    $this->putJson('/api/v1/settings/reminders', reminderInput(['offsets_days' => [], 'overdue_daily' => true]))
        ->assertOk()
        ->assertJsonPath('data.offsets_days', []);
});

it('validates the settings', function (array $input, string $field) {
    $this->putJson('/api/v1/settings/reminders', reminderInput($input))->assertJsonValidationErrors([$field]);
})->with([
    'bad time' => [['time_of_day' => '9am'], 'time_of_day'],
    'hour 24' => [['time_of_day' => '24:00'], 'time_of_day'],
    'too far ahead' => [['offsets_days' => [31]], 'offsets_days.0'],
    'repeated day' => [['offsets_days' => [2, 2]], 'offsets_days.1'],
    'missing flag' => [['enabled' => null], 'enabled'],
]);

it('keeps settings per user', function () {
    $this->putJson('/api/v1/settings/reminders', reminderInput())->assertOk();

    Sanctum::actingAs(User::factory()->create());
    $this->getJson('/api/v1/settings/reminders')->assertJsonPath('data.time_of_day', '09:00');
});
