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
import androidx.compose.ui.zIndex
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
import com.stosic.parkup.parking.ui.AddParkingFab
import com.stosic.parkup.parking.ui.AddParkingDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userEmail: String,
    onLogout: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        MapboxMapView(
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, bottom = 16.dp)
                .zIndex(10f),
            contentAlignment = Alignment.BottomStart
        ) {
            AddParkingFab(onClicked = { showAdd = true })
        }
    }
    if (showAdd) {
        AddParkingDialog(
            onDismiss = { showAdd = false },
            onSaved = { showAdd = false }
        )
    }
}

@Composable
@Suppress("MissingPermission")
private fun MapboxMapView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }
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
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid
    var mapView: MapView? by remember { mutableStateOf(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }
    var lastSentMillis by remember { mutableStateOf(0L) }
    val minSendIntervalMs = 10_000L
    var parkings by remember { mutableStateOf(emptyList<NearbyParking>()) }
    val proximityMeters = 150.0
    val notifiedIds = remember { mutableStateMapOf<String, Long>() }
    var parkingsReg by remember { mutableStateOf<ListenerRegistration?>(null) }
    var trackingListener: OnIndicatorPositionChangedListener? by remember { mutableStateOf(null) }
    var positionListener: OnIndicatorPositionChangedListener? by remember { mutableStateOf(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                mapView = this
                getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) {
                    com.stosic.parkup.parking.map.MapboxParkingOverlay.install(this)
                    if (hasLocationPermission(context)) {
                        try {
                            location.updateSettings {
                                enabled = true
                                pulsingEnabled = true
                            }
                        } catch (_: SecurityException) { }
                    }
                    positionListener = OnIndicatorPositionChangedListener { point: Point ->
                        getMapboxMap().setCamera(
                            CameraOptions.Builder()
                                .center(point)
                                .zoom(16.0)
                                .build()
                        )
                        lastUserPoint = point
                        location.removeOnIndicatorPositionChangedListener(positionListener!!)
                    }
                    location.addOnIndicatorPositionChangedListener(positionListener!!)
                    trackingListener = OnIndicatorPositionChangedListener { point: Point ->
                        lastUserPoint = point
                        maybeSendLocation(db, uid, point, lastSentMillis, minSendIntervalMs) {
                            lastSentMillis = it
                        }
                        maybeNotifyNearbyParkings(
                            context, nm, notificationChannelId, hasNotifPermission,
                            point, parkings, proximityMeters, notifiedIds
                        )
                    }
                    location.addOnIndicatorPositionChangedListener(trackingListener!!)
                }
            }
        }
    )

    DisposableEffect(uid) {
        parkingsReg = db.collection("parkings").addSnapshotListener { snap, _ ->
            val list = snap?.documents?.mapNotNull { d ->
                val lat = d.getDouble("lat") ?: return@mapNotNull null
                val lng = d.getDouble("lng") ?: return@mapNotNull null
                val title = d.getString("title") ?: "Parking"
                val createdBy = d.getString("createdBy") ?: ""
                val availableSlots = (d.getLong("availableSlots") ?: 0L)
                NearbyParking(
                    id = d.id,
                    title = title,
                    lat = lat,
                    lng = lng,
                    createdBy = createdBy,
                    available = availableSlots
                )
            } ?: emptyList()
            parkings = list
        }
        onDispose {
            parkingsReg?.remove(); parkingsReg = null
        }
    }

    LaunchedEffect(parkings, lastUserPoint) {
        val p = lastUserPoint ?: return@LaunchedEffect
        maybeNotifyNearbyParkings(
            context, nm, notificationChannelId, hasNotifPermission,
            p, parkings, proximityMeters, notifiedIds
        )
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { mapView?.onStart() }
            override fun onStop(owner: LifecycleOwner) { mapView?.onStop() }
            override fun onDestroy(owner: LifecycleOwner) {
                mapView?.onDestroy()
                parkingsReg?.remove(); parkingsReg = null
                trackingListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) }
                positionListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) }
                trackingListener = null
                positionListener = null
                com.stosic.parkup.parking.map.MapboxParkingOverlay.cleanup()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            trackingListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) }
            positionListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) }
            trackingListener = null
            positionListener = null
            com.stosic.parkup.parking.map.MapboxParkingOverlay.cleanup()
        }
    }
}

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

private data class NearbyParking(
    val id: String,
    val title: String,
    val lat: Double,
    val lng: Double,
    val createdBy: String,
    val available: Long
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

private fun maybeNotifyNearbyParkings(
    context: Context,
    nm: NotificationManagerCompat,
    channelId: String,
    hasNotifPermission: Boolean,
    me: Point,
    parkings: List<NearbyParking>,
    thresholdMeters: Double,
    notifiedIds: MutableMap<String, Long>,
) {
    if (!hasNotifPermission) return
    val now = System.currentTimeMillis()
    parkings.forEach { p ->
        if (p.available <= 0L) return@forEach
        val dist = distanceMeters(me.latitude(), me.longitude(), p.lat, p.lng)
        if (dist <= thresholdMeters) {
            val key = "parking:${p.id}"
            val last = notifiedIds[key] ?: 0L
            if (now - last >= 120_000L) {
                val title = "Parking u blizini"
                val text = "${p.title} (~${dist.roundToInt()} m)"
                val notif = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .build()
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) return
                nm.notify(key.hashCode(), notif)
                notifiedIds[key] = now
            }
        }
    }
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
