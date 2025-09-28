package com.stosic.parkup.parking.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import com.stosic.parkup.auth.data.AuthRepository

data class ParkingSpot(
    val id: String = "",
    val title: String = "",
    val address: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val capacity: Long = 0L,
    val availableSlots: Long = 0L,
    val pricePerHour: Long = 0L,
    val photoBase64: String? = null,          // NOVO
    val placeType: String = "street",         // NOVO: "street" | "garage"
    val isDisabledSpot: Boolean = false,      // NOVO: da li je mesto za invalide
    val zone: String = "green"                // NOVO: "green" | "red" | "extra"
)

object ParkingRepository {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    suspend fun addParkingSpot(
        title: String,
        address: String,
        lat: Double,
        lng: Double,
        pricePerHour: Long = 0L,
        capacity: Long = 0L,
        photoBase64: String? = null,          // NOVO
        placeType: String = "street",         // NOVO (UI šalje "street"/"garage")
        isDisabledSpot: Boolean = false,      // NOVO
        zone: String = "green"                // NOVO ("green"/"red"/"extra")
    ): Result<String> {
        return try {
            val uid = auth.currentUser?.uid ?: throw Exception("Not logged in")
            val dup = db.collection("parkings")
                .whereEqualTo("address", address.trim())
                .limit(1)
                .get()
                .await()
            if (!dup.isEmpty) {
                Result.failure(IllegalStateException("Već postoji parking na toj adresi."))
            } else {
                val doc = db.collection("parkings").document()
                val cap = if (capacity < 0) 0L else capacity

                val spot = ParkingSpot(
                    id = doc.id,
                    title = title.trim(),
                    address = address.trim(),
                    lat = lat,
                    lng = lng,
                    createdBy = uid,
                    pricePerHour = pricePerHour,
                    capacity = cap,
                    availableSlots = cap,
                    photoBase64 = photoBase64,
                    placeType = placeType,
                    isDisabledSpot = isDisabledSpot,
                    zone = zone
                )

                doc.set(spot).await()
                AuthRepository.addPoints(uid, 10)
                AuthRepository.incrementParkingCount(uid, 1)
                Result.success(doc.id)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // NOVO: update slike parkinga (samo kreator bi trebalo da zove ovo iz UI)
    suspend fun setParkingPhoto(parkingId: String, base64: String): Result<Unit> {
        return try {
            db.collection("parkings").document(parkingId)
                .set(mapOf("photoBase64" to base64), SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
