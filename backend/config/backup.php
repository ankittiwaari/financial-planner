<?php

return [
    // Filesystem disk the dumps go to. "backups" is storage/app/backups on this machine: point BACKUP_DISK at an
    // off-site disk (S3, SFTP; see config/filesystems.php) in production.
    'disk' => env('BACKUP_DISK', 'backups'),

    // How many daily dumps to keep.
    'keep' => (int) env('BACKUP_KEEP', 14),

    'mysqldump' => env('BACKUP_MYSQLDUMP', 'mysqldump'),
];
