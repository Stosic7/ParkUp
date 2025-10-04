package com.stosic.parkup.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.stosic.parkup.MainActivity
import com.stosic.parkup.R

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val type = data["type"] // "nearby_parking" | "nearby_user" | null

        val title = data["title"] ?: remoteMessage.notification?.title ?: "ParkUp"
        val body  = data["body"]  ?: remoteMessage.notification?.body  ?: "New notifications"

        val parkingId = data["parkingId"]
        val userId = data["userId"]

        val intent = Intent(this, MainActivity::class.java).apply {
            when (type) {
                "reserved_proximity" -> {
                    action = "OPEN_PARKING_DETAILS"
                    if (!parkingId.isNullOrBlank()) putExtra("parkingId", parkingId)
                }
                "nearby_parking" -> {
                    action = "OPEN_PARKING_DETAILS"
                    if (!parkingId.isNullOrBlank()) putExtra("parkingId", parkingId)
                }
                "nearby_user" -> {
                    action = "SHOW_NEARBY_USER"
                    if (!userId.isNullOrBlank()) putExtra("userId", userId)
                }
                else -> { /* default otvaranje app-a */ }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "parkup_alerts"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "ParkUp notification", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    override fun onNewToken(token: String) {
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .update(mapOf("fcmToken" to token))
        } catch (_: Exception) {
        }
    }
}