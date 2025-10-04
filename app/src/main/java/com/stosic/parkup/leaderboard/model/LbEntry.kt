package com.stosic.parkup.leaderboard.model

import com.google.firebase.firestore.DocumentSnapshot

data class LbEntry(
    val uid: String,
    val fullName: String,
    val email: String,
    val points: Long
)

val LB_COMPARATOR: Comparator<LbEntry> =
    compareByDescending<LbEntry> { it.points }
        .thenBy { it.fullName.lowercase() }
        .thenBy { it.email.lowercase() }


fun docToLbEntry(d: DocumentSnapshot): LbEntry {
    val first = (d.getString("ime") ?: "").trim()
    val last  = (d.getString("prezime") ?: "").trim()
    val email = (d.getString("email") ?: "").trim()
    val points = d.getLong("points") ?: 0L

    val fullName = listOf(first, last)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { email }

    return LbEntry(
        uid = d.id,
        fullName = fullName,
        email = email,
        points = points
    )
}
