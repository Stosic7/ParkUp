package com.stosic.parkup.parking.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
    val pricePerHour: Long = 0L
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
        capacity: Long = 0L
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
                    availableSlots = cap
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
}
