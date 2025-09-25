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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
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
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.gestures.removeOnMapClickListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.stosic.parkup.parking.ui.AddParkingFab
import com.stosic.parkup.parking.ui.AddParkingDialog
import com.stosic.parkup.parking.ui.ParkingDetailsScreen
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userEmail: String,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }

    var selected by remember { mutableStateOf<NearbyParking?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    var reserved by remember { mutableStateOf<NearbyParking?>(null) }
    var reservedDistanceMeters by remember { mutableStateOf<Double?>(null) }
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }

    Box(Modifier.fillMaxSize()) {
        MapboxMapView(
            modifier = Modifier.fillMaxSize(),
            onUserPoint = { p -> lastUserPoint = p },
            onParkingTapped = { p -> selected = p },
            onDistanceUpdateForReserved = { meters -> reservedDistanceMeters = meters },
            reservedSpot = reserved
        )

        // ADD FAB
        AnimatedVisibility(
            visible = !showDetails,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, bottom = 16.dp)
                .zIndex(20f)
        ) {
            Box(contentAlignment = Alignment.BottomStart, modifier = Modifier.fillMaxSize()) {
                AddParkingFab(onClicked = { showAdd = true })
            }
        }

        // FILTER FAB
        AnimatedVisibility(
            visible = !showDetails,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 16.dp, bottom = 16.dp)
                .zIndex(20f)
        ) {
            Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.fillMaxSize()) {
                FloatingActionButton(onClick = { /* filter kasnije */ }) {
                    Icon(Icons.Filled.FilterList, contentDescription = "Filter")
                }
            }
        }

        // Bubble pre detalja (BEZ !!)
        AnimatedVisibility(
            visible = selected != null && !showDetails && reserved == null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp)
                .padding(bottom = 128.dp)
                .zIndex(12f)
        ) {
            val sp = selected ?: return@AnimatedVisibility
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = MaterialTheme.shapes.large,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(sp.title.ifBlank { "Parking" }, fontWeight = FontWeight.Bold)
                    val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                    Text("Vreme: $now", color = Color(0xFF546E7A))
                    val cap = if (sp.capacity != null) "${sp.available}/${sp.capacity}" else "${sp.available}"
                    Text("Slobodno: $cap", color = Color(0xFF546E7A))
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { showDetails = true }) { Text("Detalji") }
                }
            }
        }

        // Baner nakon rezervacije (BEZ !!)
        AnimatedVisibility(
            visible = reserved != null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .zIndex(15f)
        ) {
            val sp = reserved ?: return@AnimatedVisibility
            val meters = reservedDistanceMeters
            val distLabel = meters?.let { if (it < 1000) "${it.roundToInt()} m" else String.format("%.1f km", it / 1000.0) } ?: "—"
            Surface(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth(),
                color = Color.White,
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Rezervacija u toku", color = Color(0xFF42A5F5), fontWeight = FontWeight.SemiBold)
                        Text(sp.title.ifBlank { "Parking" }, fontWeight = FontWeight.Bold)
                        Text("Udaljenost: $distLabel", color = Color(0xFF546E7A))
                    }
                    TextButton(onClick = { selected = sp; showDetails = true }) { Text("DETALJI") }
                }
            }
        }
    }

    if (showAdd) {
        AddParkingDialog(
            onDismiss = { showAdd = false },
            onSaved = { showAdd = false }
        )
    }

    // Detalji: rezervacija i otkazivanje se obavljaju u kartici
    if (showDetails && selected != null) {
        ParkingDetailsScreen(
            parkingId = selected!!.id,
            parkingTitle = selected!!.title.ifBlank { "Parking" },
            onBack = { showDetails = false },
            onReserved = {
                val justReserved = selected
                showDetails = false
                selected = null
                reserved = justReserved
                lastUserPoint?.let { p ->
                    justReserved?.let { r ->
                        reservedDistanceMeters = distanceMeters(p.latitude(), p.longitude(), r.lat, r.lng)
                    }
                }
            },
            onCanceled = {
                // redosled je bitan ali bez !! više nema NPE
                showDetails = false
                selected = null
                reserved = null
                reservedDistanceMeters = null
            }
        )
    }
}

@Composable
@Suppress("MissingPermission")
private fun MapboxMapView(
    modifier: Modifier = Modifier,
    onUserPoint: (Point) -> Unit,
    onParkingTapped: (NearbyParking) -> Unit,
    onDistanceUpdateForReserved: (Double) -> Unit,
    reservedSpot: NearbyParking?
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }

    // uvek najnoviji reservedSpot u listenerima
    val reservedSpotState by rememberUpdatedState(reservedSpot)

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
    var mapClickListener: OnMapClickListener? by remember { mutableStateOf(null) }

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
                        onUserPoint(point)
                        positionListener?.let { listener ->
                            try { location.removeOnIndicatorPositionChangedListener(listener) } catch (_: Exception) {}
                        }
                    }
                    positionListener?.let { location.addOnIndicatorPositionChangedListener(it) }

                    trackingListener = OnIndicatorPositionChangedListener { point: Point ->
                        lastUserPoint = point
                        onUserPoint(point)
                        maybeSendLocation(db, uid, point, lastSentMillis, minSendIntervalMs) {
                            lastSentMillis = it
                        }
                        reservedSpotState?.let { rs ->
                            val d = distanceMeters(point.latitude(), point.longitude(), rs.lat, rs.lng)
                            onDistanceUpdateForReserved(d)
                        }
                        maybeNotifyNearbyParkings(
                            context, NotificationManagerCompat.from(context), notificationChannelId,
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                            point, parkings, proximityMeters, notifiedIds
                        )
                    }
                    trackingListener?.let { location.addOnIndicatorPositionChangedListener(it) }

                    mapClickListener = OnMapClickListener { p: Point ->
                        val nearest = parkings.minByOrNull { distanceMeters(p.latitude(), p.longitude(), it.lat, it.lng) }
                        if (nearest != null) {
                            val d = distanceMeters(p.latitude(), p.longitude(), nearest.lat, nearest.lng)
                            if (d <= 50.0) {
                                onParkingTapped(nearest)
                                true
                            } else false
                        } else false
                    }
                    mapClickListener?.let { getMapboxMap().addOnMapClickListener(it) }
                }
            }
        }
    )

    DisposableEffect(uid) {
        parkingsReg = db.collection("parkings").addSnapshotListener { snap, _ ->
            val list = snap?.documents?.mapNotNull { d ->
                val lat = d.getDouble("lat") ?: return@mapNotNull null
                val lng = d.getDouble("lng") ?: return@mapNotNull null
                NearbyParking(
                    id = d.id,
                    title = d.getString("title") ?: "Parking",
                    lat = lat,
                    lng = lng,
                    createdBy = d.getString("createdBy") ?: "",
                    available = (d.getLong("availableSlots") ?: 0L),
                    capacity = d.getLong("capacity"),
                    pricePerHour = (d.getLong("pricePerHour") ?: 0L),
                    hasEv = d.getBoolean("hasEv") ?: false,
                    hasRamp = d.getBoolean("hasRamp") ?: false,
                    isCovered = d.getBoolean("isCovered") ?: false
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
            context, nm, notificationChannelId,
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            p, parkings, 150.0, notifiedIds
        )
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { mapView?.onStart() }
            override fun onStop(owner: LifecycleOwner) { mapView?.onStop() }
            override fun onDestroy(owner: LifecycleOwner) {
                mapView?.onDestroy()
                parkingsReg?.remove(); parkingsReg = null
                try { trackingListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) } } catch (_: Exception) {}
                try { positionListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) } } catch (_: Exception) {}
                try { mapClickListener?.let { mapView?.getMapboxMap()?.removeOnMapClickListener(it) } } catch (_: Exception) {}
                trackingListener = null
                positionListener = null
                mapClickListener = null
                com.stosic.parkup.parking.map.MapboxParkingOverlay.cleanup()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            try { trackingListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) } } catch (_: Exception) {}
            try { positionListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) } } catch (_: Exception) {}
            try { mapClickListener?.let { mapView?.getMapboxMap()?.removeOnMapClickListener(it) } } catch (_: Exception) {}
            trackingListener = null
            positionListener = null
            mapClickListener = null
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
    val available: Long,
    val capacity: Long?,
    val pricePerHour: Long,
    val hasEv: Boolean,
    val hasRamp: Boolean,
    val isCovered: Boolean
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
    val sinDLat = sin(dLat / 2.0)
    val sinDLon = sin(dLon / 2.0)
    val a = sinDLat * sinDLat +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sinDLon * sinDLon
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return R * c
}
