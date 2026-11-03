<?php

namespace App\Enums;

enum LoanType: string
{
    case Home = 'home';
    case Car = 'car';
    case Personal = 'personal';
    case Education = 'education';
    case Other = 'other';
}
