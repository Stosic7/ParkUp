package com.stosic.parkup.auth.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class UserProfile(
    val uid: String = "",
    val ime: String = "",
    val prezime: String = "",
    val telefon: String = "",
    val email: String = "",
    val photoUrl: String? = null
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
    ): Result<UserProfile> {
        return try {
            // kreiranje korisnika
            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val uid = authResult.user?.uid ?: throw Exception("UID je null")

            // pravimo profil
            val profile = UserProfile(uid, ime, prezime, telefon, email)

            // upis u Firestore
            db.collection("users").document(uid).set(profile).await()

            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
