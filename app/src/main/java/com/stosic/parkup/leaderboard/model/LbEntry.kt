package com.stosic.parkup.leaderboard.model

data class LbEntry(
    val uid: String,
    val fullName: String,
    val email: String,
    val points: Long
)
