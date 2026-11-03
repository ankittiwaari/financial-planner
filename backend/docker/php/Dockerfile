# Development image: PHP 8.3 CLI, matching production (composer.json pins the platform to 8.3), with the extensions
# Laravel and MySQL need, Composer, and mysqldump for backups.
FROM php:8.3-cli

RUN apt-get update \
    && apt-get install -y --no-install-recommends git unzip libzip-dev libicu-dev default-mysql-client \
    && docker-php-ext-install pdo_mysql zip intl bcmath pcntl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=composer:2 /usr/bin/composer /usr/bin/composer

WORKDIR /app
