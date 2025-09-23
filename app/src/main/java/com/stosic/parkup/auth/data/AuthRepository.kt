package com.stosic.parkup.auth.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class UserProfile(
    val uid: String = "",
    val ime: String = "",
    val prezime: String = "",
    val telefon: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val bio: String = "",
    val points: Long = 0L,
    val rank: Long = 0L
)

object AuthRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    suspend fun registerUser(
        email: String,
        password: String,
        ime: String,
        prezime: String,
        telefon: String
    ): Result<UserProfile> = try {
        val authResult = auth.createUserWithEmailAndPassword(email, password).await()
        val uid = authResult.user?.uid ?: throw Exception("UID je null")

        val profile = UserProfile(uid, ime, prezime, telefon, email)

        db.collection("users").document(uid).set(profile).await()
        Result.success(profile)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // 🔥 Atomsko uvećanje poena (kad kasnije doda parking)
    suspend fun addPoints(uid: String, delta: Long): Result<Unit> = try {
        db.collection("users").document(uid)
            .update("points", FieldValue.increment(delta))
            .await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
