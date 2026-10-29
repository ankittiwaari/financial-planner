<?php

return [

    /*
    | The single owner account, created or updated by `php artisan db:seed`.
    | There is no public sign-up endpoint.
    */
    'owner' => [
        'name' => env('OWNER_NAME', 'Owner'),
        'email' => env('OWNER_EMAIL'),
        'password' => env('OWNER_PASSWORD'),
        'timezone' => env('OWNER_TIMEZONE', 'Asia/Kolkata'),
    ],

];
