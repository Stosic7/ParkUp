package com.stosic.parkup.home

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userEmail: String,
    onLogout: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        MapboxMapView(
            modifier = Modifier.fillMaxSize()
        )
    }
}


@Composable
@Suppress("MissingPermission")
private fun MapboxMapView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }

    // ==== Notifikacije ====
    val notificationChannelId = remember { "nearby_events" }
    val nm = remember(context) { NotificationManagerCompat.from(context) }
    val wantNotifPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var hasNotifPermission by remember {
        mutableStateOf(
            !wantNotifPermission || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notifPermissionLauncher = if (wantNotifPermission) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotifPermission = granted
        }
    } else null

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                notificationChannelId,
                "Blizina (ParkUp)",
                NotificationManager.IMPORTANCE_HIGH
            )
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        if (wantNotifPermission && !hasNotifPermission) {
            notifPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ==== Lokacione permisije ====
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission =
            (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                    (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (!hasPermission) {
        PermissionRationale(
            onRequest = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                activity?.startActivity(intent)
            }
        )
        return
    }

    // ==== Firebase refs ====
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var mapView: MapView? by remember { mutableStateOf(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var lastUserPoint by remember { mutableStateOf<Point?>(null) }
    var lastSentMillis by remember { mutableStateOf(0L) }
    val minSendIntervalMs = 10_000L // šalji na ~10s

    var objects by remember { mutableStateOf(emptyList<NearbyItem>()) }
    var others by remember { mutableStateOf(emptyList<NearbyItem>()) }
    val proximityMeters = 150.0
    val notifiedIds = remember { mutableStateMapOf<String, Long>() }

    var objectsReg by remember { mutableStateOf<ListenerRegistration?>(null) }
    var othersReg by remember { mutableStateOf<ListenerRegistration?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                mapView = this
                getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) {
                    if (hasLocationPermission(context)) {
                        try {
                            location.updateSettings {
                                enabled = true
                                pulsingEnabled = true
                            }
                        } catch (se: SecurityException) {
                            se.printStackTrace()
                        }
                    }

                    lateinit var positionListener: OnIndicatorPositionChangedListener
                    positionListener = OnIndicatorPositionChangedListener { point: Point ->
                        getMapboxMap().setCamera(
                            CameraOptions.Builder()
                                .center(point)
                                .zoom(16.0)
                                .build()
                        )
                        lastUserPoint = point
                        location.removeOnIndicatorPositionChangedListener(positionListener)
                    }
                    location.addOnIndicatorPositionChangedListener(positionListener)

                    val trackingListener = OnIndicatorPositionChangedListener { point: Point ->
                        lastUserPoint = point
                        maybeSendLocation(db, uid, point, lastSentMillis, minSendIntervalMs) {
                            lastSentMillis = it
                        }
                        maybeNotifyNearby(
                            context, nm, notificationChannelId, hasNotifPermission,
                            point, objects, others, proximityMeters, notifiedIds
                        )
                    }
                    location.addOnIndicatorPositionChangedListener(trackingListener)
                }
            }
        }
    )

    // Firestore listeners
    LaunchedEffect(uid) {
        objectsReg?.remove()
        objectsReg = db.collection("objects").addSnapshotListener { snap, _ ->
            val list = snap?.documents?.mapNotNull { d ->
                val lat = d.getDouble("lat") ?: return@mapNotNull null
                val lng = d.getDouble("lng") ?: return@mapNotNull null
                val name = d.getString("name") ?: "Objekat"
                val type = d.getString("type") ?: "object"
                NearbyItem("obj:${d.id}", name, type, lat, lng)
            } ?: emptyList()
            objects = list
            lastUserPoint?.let {
                maybeNotifyNearby(
                    context, nm, notificationChannelId, hasNotifPermission,
                    it, objects, others, proximityMeters, notifiedIds
                )
            }
        }

        othersReg?.remove()
        othersReg = db.collection("locations").addSnapshotListener { snap, _ ->
            val me = uid
            val list = snap?.documents?.mapNotNull { d ->
                val otherUid = d.id
                if (otherUid == null || otherUid == me) return@mapNotNull null
                val lat = d.getDouble("lat") ?: return@mapNotNull null
                val lng = d.getDouble("lng") ?: return@mapNotNull null
                val label = d.getString("email") ?: "Korisnik"
                NearbyItem("usr:$otherUid", label, "user", lat, lng)
            } ?: emptyList()
            others = list
            lastUserPoint?.let {
                maybeNotifyNearby(
                    context, nm, notificationChannelId, hasNotifPermission,
                    it, objects, others, proximityMeters, notifiedIds
                )
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { mapView?.onStart() }
            override fun onStop(owner: LifecycleOwner) { mapView?.onStop() }
            override fun onDestroy(owner: LifecycleOwner) {
                mapView?.onDestroy()
                objectsReg?.remove(); objectsReg = null
                othersReg?.remove(); othersReg = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }
}

// ——— Helpers ———

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

@Composable
private fun PermissionRationale(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Potrebna je dozvola za lokaciju da bi se prikazala tvoja pozicija na mapi.")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRequest) { Text("Dozvoli") }
            Button(onClick = onOpenSettings) { Text("Podešavanja") }
        }
    }
}

private data class NearbyItem(
    val id: String,
    val name: String,
    val type: String,
    val lat: Double,
    val lng: Double
)

private fun maybeSendLocation(
    db: FirebaseFirestore,
    uid: String?,
    point: Point,
    lastSentMillis: Long,
    minIntervalMs: Long,
    onSent: (newTs: Long) -> Unit
) {
    if (uid == null) return
    val now = System.currentTimeMillis()
    if (now - lastSentMillis < minIntervalMs) return

    val data = hashMapOf(
        "lat" to point.latitude(),
        "lng" to point.longitude(),
        "ts" to now,
        "email" to (FirebaseAuth.getInstance().currentUser?.email ?: "")
    )
    db.collection("locations").document(uid).set(data)
        .addOnSuccessListener { onSent(now) }
}

private fun maybeNotifyNearby(
    context: Context,
    nm: NotificationManagerCompat,
    channelId: String,
    hasNotifPermission: Boolean,
    me: Point,
    objects: List<NearbyItem>,
    others: List<NearbyItem>,
    thresholdMeters: Double,
    notifiedIds: MutableMap<String, Long>,
) {
    if (!hasNotifPermission) return
    val now = System.currentTimeMillis()

    fun consider(item: NearbyItem) {
        val dist = distanceMeters(me.latitude(), me.longitude(), item.lat, item.lng)
        if (dist <= thresholdMeters) {
            val key = "${item.type}:${item.id}"
            val last = notifiedIds[key] ?: 0L
            if (now - last >= 120_000L) {
                val title = if (item.type == "user") "Korisnik u blizini" else "Objekat u blizini"
                val text = "${item.name} (~${dist.roundToInt()} m)"
                val notif = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setFullScreenIntent(null, true)
                    .setAutoCancel(true)
                    .build()

                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }

                nm.notify(key.hashCode(), notif)
                notifiedIds[key] = now
            }
        }
    }

    objects.forEach(::consider)
    others.forEach(::consider)
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}
