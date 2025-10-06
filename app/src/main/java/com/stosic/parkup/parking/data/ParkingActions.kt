package com.stosic.parkup.parking.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import com.stosic.parkup.auth.data.AuthRepository

// Firestore model for commenting
data class ParkingComment(
    val id: String = "",
    val uid: String = "",
    val text: String = "",
    val likes: Long = 0L,
    val dislikes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

// One "service" object for actions over the parking lot; centralizes Firebase calls
object ParkingActions {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Reserve a slot: atomically reduce availableSlots and write "active" reservation under users/{uid}/reservations/{parkingId}
    suspend fun reserveSpot(parkingId: String): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not logged in"))
            db.runTransaction { tr ->
                val pref = db.collection("parkings").document(parkingId)
                val psnap = tr.get(pref)

                val available = (psnap.getLong("availableSlots") ?: 0L)
                if (available <= 0L) throw IllegalStateException("No available parking spots.")

                // Decrement availableSlots
                if (!psnap.exists()) {
                    tr.set(pref, mapOf("availableSlots" to (available - 1L)), SetOptions.merge())
                } else {
                    tr.update(pref, "availableSlots", available - 1L)
                }

                // Record the reservation under the user
                val rref = db.collection("users").document(uid)
                    .collection("reservations").document(parkingId)
                tr.set(
                    rref,
                    mapOf(
                        "parkingId" to parkingId,
                        "createdAt" to System.currentTimeMillis(),
                        "status" to "active"
                    ),
                    SetOptions.merge()
                )
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Completing the reservation: atomically increase availableSlots (up to 'capacity') and set the reservation status to "finished"
    suspend fun finishReservation(parkingId: String): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not logged in"))
            db.runTransaction { tr ->
                val pref = db.collection("parkings").document(parkingId)
                val psnap = tr.get(pref)

                // capacity + 1, not above the limit
                val capacity = psnap.getLong("capacity") ?: Long.MAX_VALUE
                val available = psnap.getLong("availableSlots") ?: 0L
                val newAvailable = (available + 1L).coerceAtMost(capacity)

                if (!psnap.exists()) {
                    tr.set(pref, mapOf("availableSlots" to newAvailable), SetOptions.merge())
                } else {
                    tr.update(pref, "availableSlots", newAvailable)
                }

                // change from active to finished for the user
                val rref = db.collection("users").document(uid)
                    .collection("reservations").document(parkingId)
                tr.set(
                    rref,
                    mapOf(
                        "parkingId" to parkingId,
                        "updatedAt" to System.currentTimeMillis(),
                        "status" to "finished"
                    ),
                    SetOptions.merge()
                )
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Add a comment under parkings/{parkingId}/comments/{autoId}; returns the id of the comment
    suspend fun addComment(parkingId: String, text: String): Result<String> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not logged in"))
            val doc = db.collection("parkings").document(parkingId)
                .collection("comments").document()
            val comment = ParkingComment(id = doc.id, uid = uid, text = text.trim())
            doc.set(comment).await()
            Result.success(doc.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Vote for a comment (like/dislike), save the user's vote in comments/{commentId}/votes/{uid} to prevent duplicate votes
    // And you update the counters on the comment; the author gets +2 for likes, -1 for dislikes
    suspend fun voteComment(
        parkingId: String,
        commentId: String,
        commentAuthorUid: String,
        vote: String // "like" or "dislike"
    ): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not logged in"))
            if (uid == commentAuthorUid) return Result.failure(IllegalStateException("You can’t vote on your own comment."))

            val cref = db.collection("parkings").document(parkingId)
                .collection("comments").document(commentId)
            val vref = cref.collection("votes").document(uid)

            db.runTransaction { tr ->
                val vsnap = tr.get(vref)
                val prev = vsnap.getString("vote")

                if (prev == vote) {
                    return@runTransaction
                }

                if (vote == "like") {
                    tr.update(cref, "likes", FieldValue.increment(1))
                    if (prev == "dislike") tr.update(cref, "dislikes", FieldValue.increment(-1))
                } else {
                    tr.update(cref, "dislikes", FieldValue.increment(1))
                    if (prev == "like") tr.update(cref, "likes", FieldValue.increment(-1))
                }

                tr.set(vref, mapOf("vote" to vote), SetOptions.merge())
            }.await()

            if (vote == "like") {
                // author +2 points
                AuthRepository.addPoints(commentAuthorUid, 2)
            }

            if (vote == "dislike") {
                // author -1 point
                AuthRepository.addPoints(commentAuthorUid, -1);
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
