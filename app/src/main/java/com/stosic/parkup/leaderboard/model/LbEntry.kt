package com.stosic.parkup.leaderboard.model

import com.google.firebase.firestore.DocumentSnapshot

// how does one row in the table looks like
data class LbEntry(
    val uid: String,
    val fullName: String,
    val email: String,
    val points: Long
)

// Sort: points desc, then fullName asc (case-insensitive), then email asc
val LB_COMPARATOR: Comparator<LbEntry> =
    compareByDescending<LbEntry> { it.points }
        .thenBy { it.fullName.lowercase() }
        .thenBy { it.email.lowercase() }

// Convert Firestore user doc -> LbEntry (for leaderboard UI)
fun docToLbEntry(d: DocumentSnapshot): LbEntry {
    val first = (d.getString("ime") ?: "").trim()
    val last  = (d.getString("prezime") ?: "").trim()
    val email = (d.getString("email") ?: "").trim()
    val points = d.getLong("points") ?: 0L

    // Build display name; if both names missing, fall back to email
    val fullName = listOf(first, last)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { email }

    // Return leaderboard entry with document ID as uid
    return LbEntry(
        uid = d.id,
        fullName = fullName,
        email = email,
        points = points
    )
}
