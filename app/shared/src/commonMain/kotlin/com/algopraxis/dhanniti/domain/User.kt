package com.algopraxis.dhanniti.domain

data class User(
    val id: Long,
    val name: String,
    val email: String,
    val timezone: String,
    val currency: String,
)
