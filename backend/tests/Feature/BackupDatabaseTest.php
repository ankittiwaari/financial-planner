<?php

use Illuminate\Process\PendingProcess;
use Illuminate\Support\Facades\Process;
use Illuminate\Support\Facades\Storage;

beforeEach(function () {
    Storage::fake('backups');
    config([
        'database.default' => 'mysql',
        'database.connections.mysql' => [
            'driver' => 'mysql', 'host' => 'mysql', 'port' => 3306, 'database' => 'dhanniti', 'username' => 'app', 'password' => 's3cret',
        ],
    ]);
});

it('dumps the database gzipped to the backups disk, keeping the password off the command line', function () {
    Process::fake(['*' => Process::result(output: "CREATE TABLE users (id int);\n")]);
    $this->travelTo(now()->setDate(2026, 11, 3)->setTime(21, 0));

    $this->artisan('backup:database')->assertSuccessful();

    $files = Storage::disk('backups')->files();
    expect($files)->toBe(['dhanniti-2026-11-03T210000Z.sql.gz'])
        ->and(gzdecode(Storage::disk('backups')->get($files[0])))->toBe("CREATE TABLE users (id int);\n");
    Process::assertRan(fn (PendingProcess $process) => $process->command[0] === 'mysqldump'
        && in_array('--single-transaction', $process->command, true)
        && in_array('dhanniti', $process->command, true)
        && $process->environment === ['MYSQL_PWD' => 's3cret']
        && ! str_contains(implode(' ', $process->command), 's3cret'));
});

it('keeps only the newest copies', function () {
    Process::fake(['*' => Process::result(output: 'dump')]);
    config(['backup.keep' => 2]);
    foreach (['2026-10-30', '2026-10-31', '2026-11-01'] as $day) {
        Storage::disk('backups')->put("dhanniti-{$day}T210000Z.sql.gz", 'old');
    }
    Storage::disk('backups')->put('notes.txt', 'not a backup');
    $this->travelTo(now()->setDate(2026, 11, 3)->setTime(21, 0));

    $this->artisan('backup:database')->assertSuccessful();

    expect(Storage::disk('backups')->files())->toEqualCanonicalizing([
        'dhanniti-2026-11-01T210000Z.sql.gz', 'dhanniti-2026-11-03T210000Z.sql.gz', 'notes.txt',
    ]);
});

it('fails loudly when mysqldump does, storing nothing', function () {
    Process::fake(['*' => Process::result(errorOutput: 'Access denied for user', exitCode: 2)]);

    $this->artisan('backup:database')->expectsOutputToContain('mysqldump failed: Access denied for user')->assertFailed();

    expect(Storage::disk('backups')->files())->toBe([]);
});
