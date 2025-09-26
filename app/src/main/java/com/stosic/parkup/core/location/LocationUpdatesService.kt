package com.stosic.parkup.core.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.stosic.parkup.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocationUpdatesService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var client: FusedLocationProviderClient
    private lateinit var req: LocationRequest

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        req = LocationRequest.Builder(
            /* priority = */ Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            /* intervalMillis = */ 60_000L // 60s
        )
            .setMinUpdateDistanceMeters(25f) // 25 m
            .setWaitForAccurateLocation(false)
            .build()

        startForeground(1, buildForegroundNotification())
        startLocationUpdates()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        // If killed by system, try to restart
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { client.removeLocationUpdates(cb) } catch (_: SecurityException) { }
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = null

    private fun buildForegroundNotification(): Notification {
        val channelId = "parkup_loc_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                channelId,
                "Praćenje lokacije",
                NotificationManager.IMPORTANCE_MIN
            )
            nm.createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle("ParkUp")
            .setContentText("Ažuriranje lokacije za obaveštenja")
            .setOngoing(true)
            .build()
    }

    private val cb = object : LocationCallback() {
        override fun onLocationResult(res: LocationResult) {
            val loc = res.lastLocation ?: return
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val db = FirebaseFirestore.getInstance()
            scope.launch {
                val data = hashMapOf<String, Any>(
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "lastLocAt" to System.currentTimeMillis()
                )
                db.collection("users").document(uid).update(data)
            }
        }
    }

    private fun startLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            // Nemamo permisiju – ništa, servis će raditi sledeći put kad se odobri.
            return
        }
        try {
            client.requestLocationUpdates(req, cb, mainLooper)
        } catch (_: SecurityException) {
            // Ako korisnik ukine permisiju u hodu
        }
    }
}
