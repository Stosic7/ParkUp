package com.stosic.parkup.parking.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import com.stosic.parkup.auth.data.AuthRepository

data class ParkingComment(
    val id: String = "",
    val uid: String = "",
    val text: String = "",
    val likes: Long = 0L,
    val dislikes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

object ParkingActions {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    suspend fun reserveSpot(parkingId: String): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not logged in"))
            db.runTransaction { tr ->
                val pref = db.collection("parkings").document(parkingId)
                val psnap = tr.get(pref)

                val available = (psnap.getLong("availableSlots") ?: 0L)
                if (available <= 0L) throw IllegalStateException("Nema slobodnih mesta.")

                if (!psnap.exists()) {
                    tr.set(pref, mapOf("availableSlots" to (available - 1L)), SetOptions.merge())
                } else {
                    tr.update(pref, "availableSlots", available - 1L)
                }

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

    suspend fun finishReservation(parkingId: String): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not logged in"))
            db.runTransaction { tr ->
                val pref = db.collection("parkings").document(parkingId)
                val psnap = tr.get(pref)

                val capacity = psnap.getLong("capacity") ?: Long.MAX_VALUE
                val available = psnap.getLong("availableSlots") ?: 0L
                val newAvailable = (available + 1L).coerceAtMost(capacity)

                if (!psnap.exists()) {
                    tr.set(pref, mapOf("availableSlots" to newAvailable), SetOptions.merge())
                } else {
                    tr.update(pref, "availableSlots", newAvailable)
                }

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

    suspend fun setRating(parkingId: String, stars: Int): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not logged in"))
            val s = stars.coerceIn(1, 5)
            val rdoc = db.collection("parkings").document(parkingId)
                .collection("ratings").document(uid)
            rdoc.set(mapOf("stars" to s, "updatedAt" to System.currentTimeMillis())).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAverageRating(parkingId: String): Result<Double> {
        return try {
            val coll = db.collection("parkings").document(parkingId).collection("ratings")
            val qs = coll.get().await()
            val vals = qs.documents.mapNotNull { it.getLong("stars")?.toDouble() }
            val avg = if (vals.isEmpty()) 0.0 else vals.sum() / vals.size
            Result.success(avg)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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

    suspend fun likeComment(parkingId: String, commentId: String, commentAuthorUid: String): Result<Unit> {
        return try {
            val cref = db.collection("parkings").document(parkingId)
                .collection("comments").document(commentId)
            db.runTransaction { tr ->
                tr.update(cref, "likes", FieldValue.increment(1))
            }.await()
            AuthRepository.addPoints(commentAuthorUid, 2)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun dislikeComment(parkingId: String, commentId: String): Result<Unit> {
        return try {
            val cref = db.collection("parkings").document(parkingId)
                .collection("comments").document(commentId)
            db.runTransaction { tr ->
                tr.update(cref, "dislikes", FieldValue.increment(1))
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
