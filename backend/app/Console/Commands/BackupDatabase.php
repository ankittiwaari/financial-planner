<?php

namespace App\Console\Commands;

use Illuminate\Console\Command;
use Illuminate\Support\Facades\Process;
use Illuminate\Support\Facades\Storage;
use RuntimeException;

/**
 * Dumps the database, gzipped, to the "backups" disk and keeps the newest BACKUP_KEEP copies. Point BACKUP_DISK at
 * an off-site disk (S3, SFTP) in production; the default is storage/app/backups on this machine.
 */
class BackupDatabase extends Command
{
    protected $signature = 'backup:database';

    protected $description = 'Dump the database to the backups disk and prune old copies';

    public function handle(): int
    {
        $connection = config('database.default');
        $config = config("database.connections.{$connection}");
        $name = 'dhanniti-'.now()->utc()->format('Y-m-d\THis\Z').'.sql.gz';

        try {
            $sql = match ($config['driver']) {
                'mysql', 'mariadb' => $this->mysqlDump($config),
                'sqlite' => $this->sqliteCopy($config),
                default => throw new RuntimeException("Backups aren't supported for the {$config['driver']} driver."),
            };
        } catch (RuntimeException $e) {
            $this->error($e->getMessage());

            return self::FAILURE;
        }

        $disk = Storage::disk(config('backup.disk'));
        $disk->put($name, gzencode($sql, 9));
        $this->info("Backed up to {$name} on the ".config('backup.disk').' disk ('.number_format(strlen($sql) / 1024, 1).' KB before compression).');

        $this->prune($disk);

        return self::SUCCESS;
    }

    /** @param array<string, mixed> $config */
    private function mysqlDump(array $config): string
    {
        // --single-transaction: a consistent snapshot without locking InnoDB tables. The password goes via the
        // environment, never the command line, where other processes could see it.
        $result = Process::env(['MYSQL_PWD' => (string) $config['password']])
            ->timeout(600)
            ->run([
                config('backup.mysqldump'),
                '--host='.$config['host'],
                '--port='.$config['port'],
                '--user='.$config['username'],
                '--single-transaction',
                '--routines',
                '--no-tablespaces',
                $config['database'],
            ]);
        if ($result->failed()) {
            throw new RuntimeException('mysqldump failed: '.trim($result->errorOutput()));
        }

        return $result->output();
    }

    /** @param array<string, mixed> $config */
    private function sqliteCopy(array $config): string
    {
        $path = $config['database'];
        if ($path === ':memory:' || ! is_file($path)) {
            throw new RuntimeException("There's no SQLite file to back up.");
        }

        return file_get_contents($path);
    }

    private function prune(\Illuminate\Contracts\Filesystem\Filesystem $disk): void
    {
        $keep = max(1, (int) config('backup.keep'));
        // Names sort by time, so the oldest come first.
        $old = collect($disk->files())
            ->filter(fn (string $file) => str_starts_with($file, 'dhanniti-') && str_ends_with($file, '.sql.gz'))
            ->sort()
            ->values()
            ->slice(0, -$keep);
        $old->each(fn (string $file) => $disk->delete($file));
        if ($old->isNotEmpty()) {
            $this->line("Removed {$old->count()} old backup(s), keeping the newest {$keep}.");
        }
    }
}
