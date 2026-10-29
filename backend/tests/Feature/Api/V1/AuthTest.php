<?php

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\PersonalAccessToken;

uses(RefreshDatabase::class);

beforeEach(function () {
    $this->user = User::factory()->create([
        'name' => 'Ankit',
        'email' => 'owner@dhanniti.test',
        'password' => 'correct-horse',
    ]);
});

function login(array $overrides = []): Illuminate\Testing\TestResponse
{
    return test()->postJson('/api/v1/auth/login', array_merge([
        'email' => 'owner@dhanniti.test',
        'password' => 'correct-horse',
        'device_name' => 'Pixel 9',
    ], $overrides));
}

it('reports health without auth', function () {
    $this->getJson('/api/v1/health')->assertOk()->assertExactJson(['status' => 'ok']);
});

it('logs in and returns a token with the user profile', function () {
    login()
        ->assertOk()
        ->assertJsonStructure(['token', 'user' => ['id', 'name', 'email', 'timezone', 'currency', 'cycle_mode']])
        ->assertJsonPath('user.email', 'owner@dhanniti.test')
        ->assertJsonPath('user.timezone', 'Asia/Kolkata')
        ->assertJsonPath('user.currency', 'INR')
        ->assertJsonPath('user.cycle_mode', 'payday');

    expect($this->user->tokens()->sole()->name)->toBe('Pixel 9');
});

it('accepts the email in any case', function () {
    login(['email' => 'Owner@DhanNiti.test'])->assertOk();
});

it('rejects a wrong password with a 422 on email', function () {
    login(['password' => 'wrong'])
        ->assertUnprocessable()
        ->assertJsonValidationErrors(['email']);

    expect(PersonalAccessToken::count())->toBe(0);
});

it('requires email, password and device name', function () {
    $this->postJson('/api/v1/auth/login', [])
        ->assertUnprocessable()
        ->assertJsonValidationErrors(['email', 'password', 'device_name']);
});

it('throttles repeated login attempts', function () {
    foreach (range(1, 5) as $_) {
        login(['password' => 'wrong'])->assertUnprocessable();
    }

    login(['password' => 'wrong'])->assertTooManyRequests();
});

it('returns 401 json for /me without a token', function () {
    // No Accept header: API routes must still answer with JSON, not a redirect to a login page.
    $this->get('/api/v1/me')
        ->assertUnauthorized()
        ->assertJson(['message' => 'Unauthenticated.']);
});

it('returns the current user for a valid token', function () {
    $token = login()->json('token');

    $this->withToken($token)->getJson('/api/v1/me')
        ->assertOk()
        ->assertJsonPath('user.id', $this->user->id)
        ->assertJsonPath('user.name', 'Ankit');
});

it('revokes only the current token on logout', function () {
    $phone = login()->json('token');
    $tablet = login(['device_name' => 'Tablet'])->json('token');

    $this->withToken($phone)->postJson('/api/v1/auth/logout')->assertNoContent();

    // Reset the auth guard so the next request re-reads the token from the database.
    $this->app['auth']->forgetGuards();
    $this->withToken($phone)->getJson('/api/v1/me')->assertUnauthorized();

    $this->app['auth']->forgetGuards();
    $this->withToken($tablet)->getJson('/api/v1/me')->assertOk();
});
