package com.example.notitia

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform