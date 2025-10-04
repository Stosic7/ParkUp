package com.stosic.parkup.home

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.stosic.parkup.parking.map.MapboxParkingOverlay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.android.gestures.MoveGestureDetector
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
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import android.content.BroadcastReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

private data class ParkingFilter(
    val onlyAvailable: Boolean = false,
    val maxPricePerHour: Long? = null,
    val hasEv: Boolean = false,
    val hasRamp: Boolean = false,
    val isCovered: Boolean = false,
    var hasDisabledSpot : Boolean = false,
    val maxDistanceMeters: Double? = null,
    val authorQuery: String = "",
    // Date/Time — supports "yyyy-MM-dd" or "yyyy-MM-dd HH:mm"
    val fromDate: String = "",
    val toDate: String = ""
) {
    val isActive: Boolean
        get() = onlyAvailable ||
                maxPricePerHour != null ||
                hasEv || hasRamp || isCovered ||
                maxDistanceMeters != null ||
                authorQuery.isNotBlank() ||
                fromDate.isNotBlank() || toDate.isNotBlank()
}

private fun ParkingFilter.matches(p: NearbyParking, userPoint: Point?): Boolean {
    if (onlyAvailable && p.available <= 0L) return false
    if (maxPricePerHour != null && p.pricePerHour > maxPricePerHour) return false
    if (hasEv && !p.hasEv) return false
    if (hasRamp && !p.hasRamp) return false
    if (isCovered && !p.isCovered) return false
    if (maxDistanceMeters != null && userPoint != null) {
        val d = distanceMeters(userPoint.latitude(), userPoint.longitude(), p.lat, p.lng)
        if (d > maxDistanceMeters) return false
    }
    if (authorQuery.isNotBlank()) {
        val email = p.createdByEmail.lowercase()
        if (!email.contains(authorQuery.lowercase())) return false
    }
    return true
}

private data class FocusTarget(
    val lat: Double,
    val lng: Double,
    val zoom: Double = 17.0,
    val nonce: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userEmail: String,
    onLogout: () -> Unit,
    onOverlayVisible: (Boolean) -> Unit = {},
    onUiLockedChange: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<NearbyParking?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var reserved by remember { mutableStateOf<NearbyParking?>(null) }
    var reservedDistanceMeters by remember { mutableStateOf<Double?>(null) }
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }
    var showFilter by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ParkingFilter()) }
    var showFilteredList by remember { mutableStateOf(false) }
    var allParkings by remember { mutableStateOf(emptyList<NearbyParking>()) }

    fun parseDateFlexible(s: String): Long? {
        if (s.isBlank()) return null
        val patterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (pat in patterns) {
            try {
                val fmt = SimpleDateFormat(pat, Locale.US)
                fmt.isLenient = false
                val t = fmt.parse(s)?.time
                if (t != null) return t
            } catch (_: ParseException) { }
        }
        return null
    }

    val filtered = remember(allParkings, filter, lastUserPoint) {
        val base = allParkings.filter { filter.matches(it, lastUserPoint) }
        val fromMillis = parseDateFlexible(filter.fromDate) ?: Long.MIN_VALUE
        val toRaw = parseDateFlexible(filter.toDate)
        val toMillisIncl = if (toRaw != null) {
            if (filter.toDate.contains(" ")) {
                toRaw
            } else {
                toRaw + (24L * 60 * 60 * 1000 - 1)
            }
        } else Long.MAX_VALUE

        base.filter { row ->
            val timeOk = row.createdAt in fromMillis..toMillisIncl
            timeOk
        }.sortedBy { p ->
            lastUserPoint?.let { distanceMeters(it.latitude(), it.longitude(), p.lat, p.lng) } ?: Double.MAX_VALUE
        }
    }

    var focusTarget by remember { mutableStateOf<FocusTarget?>(null) }
    var showRecenter by remember { mutableStateOf(false) }
    var recenterSignal by remember { mutableStateOf<Long?>(null) }

    val overlayVisible = (!showDetails && reserved == null)
    LaunchedEffect(showDetails, reserved) { onOverlayVisible(overlayVisible) }
    LaunchedEffect(reserved) { onUiLockedChange(reserved != null) }

    Box(Modifier.fillMaxSize()) {
        MapboxMapView(
            modifier = Modifier.fillMaxSize(),
            onUserPoint = { p -> lastUserPoint = p },
            onParkingTapped = { p -> selected = p },
            onDistanceUpdateForReserved = { meters -> reservedDistanceMeters = meters },
            reservedSpot = reserved,
            filter = filter,
            userPointForFilter = lastUserPoint,
            onParkingsChanged = { list -> allParkings = list },
            focusTarget = focusTarget,
            onUserMapGesture = { showRecenter = true },
            recenterSignal = recenterSignal
        )

        AnimatedVisibility(
            visible = !showDetails && reserved == null,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, bottom = 16.dp)
                .zIndex(20f)
        ) {
            Box(contentAlignment = Alignment.BottomStart, modifier = Modifier.fillMaxSize()) {
                AddParkingFab(onClicked = { showAdd = true })
            }
        }

        AnimatedVisibility(
            visible = !showDetails && reserved == null,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 16.dp, bottom = 16.dp)
                .zIndex(20f)
        ) {
            Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.fillMaxSize()) {
                FloatingActionButton(onClick = {
                    showFilter = true
                }) {
                    Box {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filter")
                        if (filter.isActive) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .background(Color(0xFF2E7D32), CircleShape)
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showRecenter && !showDetails,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 16.dp, bottom = 88.dp)
                .zIndex(21f)
        ) {
            Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.fillMaxSize()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        recenterSignal = System.currentTimeMillis()
                        showRecenter = false
                    },
                    icon = { Icon(Icons.Filled.MyLocation, contentDescription = "Centriraj me") },
                    text = { Text("Recenter") }
                )
            }
        }

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(sp.title.ifBlank { "Parking" }, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { selected = null }) { Text("Close") }
                    }

                    val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                    Text("Time: $now", color = Color(0xFF546E7A))
                    val cap = if (sp.capacity != null) "${sp.available}/${sp.capacity}" else "${sp.available}"
                    Text("Available: $cap", color = Color(0xFF546E7A))
                    Spacer(Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { showDetails = true }, modifier = Modifier.weight(1f)) { Text("Details") }
                    }
                }
            }
        }

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
                        Text("Reservation in progress", color = Color(0xFF42A5F5), fontWeight = FontWeight.SemiBold)
                        Text(sp.title.ifBlank { "Parking" }, fontWeight = FontWeight.Bold)
                        Text("Distance: $distLabel", color = Color(0xFF546E7A))
                    }
                    TextButton(onClick = { selected = sp; showDetails = true }) { Text("DETAILS") }
                }
            }
        }

        AnimatedVisibility(
            visible = showFilteredList && !showDetails,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(220)) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(180)) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 64.dp, start = 8.dp, end = 8.dp)
                .zIndex(18f)
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                color = Color.White,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 420.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Results (${filtered.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = { showFilteredList = false }) { Text("Close") }
                    }
                    Divider()
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("No parking matches your filters.") }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filtered) { p ->
                                FilterResultRow(
                                    item = p,
                                    distanceMeters = lastUserPoint?.let {
                                        distanceMeters(it.latitude(), it.longitude(), p.lat, p.lng)
                                    },
                                    onShowOnMap = {
                                        focusTarget = FocusTarget(p.lat, p.lng, 17.0)
                                        showFilteredList = false
                                        selected = p
                                        showRecenter = false
                                    }
                                )
                            }
                        }
                    }
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
                        val mePoint = lastUserPoint
                        if (mePoint != null) {
                            maybeNotifyReservedSpotNearby(
                                context = ctx,
                                nm = NotificationManagerCompat.from(ctx),
                                channelId = "nearby_events",
                                hasNotifPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                                me = mePoint,
                                reserved = justReserved,
                                thresholdMeters = 150.0,
                                notifiedIds = mutableMapOf()
                            )
                        }
                    }
                }
            },
            onCanceled = {
                showDetails = false
                selected = null
                reserved = null
                reservedDistanceMeters = null
            }
        )
    }

    if (showFilter) {
        ModalBottomSheet(onDismissRequest = { showFilter = false }) {
            FilterSheet(
                initial = filter,
                onApply = { new ->
                    filter = new
                    showFilter = false
                    showFilteredList = true
                },
                onReset = {
                    filter = ParkingFilter()
                    showFilter = false
                    showFilteredList = false
                }
            )
        }
    }
}

@Composable
private fun FilterSheet(
    initial: ParkingFilter,
    onApply: (ParkingFilter) -> Unit,
    onReset: () -> Unit
) {
    var onlyAvailable by remember { mutableStateOf(initial.onlyAvailable) }
    var maxPriceText by remember { mutableStateOf(initial.maxPricePerHour?.toString().orEmpty()) }
    var hasEv by remember { mutableStateOf(initial.hasEv) }
    var hasRamp by remember { mutableStateOf(initial.hasRamp) }
    var maxDistText by remember { mutableStateOf(initial.maxDistanceMeters?.roundToInt()?.toString().orEmpty()) }
    var authorText by remember { mutableStateOf(initial.authorQuery) }
    var fromDate by remember { mutableStateOf(initial.fromDate) }
    var toDate by remember { mutableStateOf(initial.toDate) }
    var hasDisabled by remember { mutableStateOf(initial.hasDisabledSpot) }



    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Filters", style = MaterialTheme.typography.titleMedium)
        Divider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = onlyAvailable, onCheckedChange = { onlyAvailable = it })
            Spacer(Modifier.width(8.dp))
            Text("Only available")
        }

        OutlinedTextField(
            value = maxPriceText,
            onValueChange = { s -> if (s.all { it.isDigit() }) maxPriceText = s },
            label = { Text("Max hourly price (RSD)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChipSwitch("EV charger", hasEv) { hasEv = it }
            FilterChipSwitch("Ramp", hasRamp) { hasRamp = it }
            FilterChipSwitch("Accessible spot", hasDisabled) { hasDisabled = it }
        }

        OutlinedTextField(
            value = maxDistText,
            onValueChange = { s -> if (s.all { it.isDigit() }) maxDistText = s },
            label = { Text("Max distance (m)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = authorText,
            onValueChange = { authorText = it },
            label = { Text("Author (email)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = fromDate,
                onValueChange = { fromDate = it },
                label = { Text("From (yyyy-MM-dd or yyyy-MM-dd HH:mm)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = toDate,
                onValueChange = { toDate = it },
                label = { Text("To (yyyy-MM-dd or yyyy-MM-dd HH:mm)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Reset") }
            Button(
                onClick = {
                    val new = ParkingFilter(
                        onlyAvailable = onlyAvailable,
                        maxPricePerHour = maxPriceText.toLongOrNull(),
                        hasEv = hasEv,
                        hasRamp = hasRamp,
                        hasDisabledSpot = hasDisabled,
                        maxDistanceMeters = maxDistText.toDoubleOrNull(),
                        authorQuery = authorText,
                        fromDate = fromDate.trim(),
                        toDate = toDate.trim()
                    )
                    onApply(new)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Apply") }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FilterChipSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    AssistChip(
        onClick = { onCheckedChange(!checked) },
        label = { Text(label) },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (checked) Color(0xFF2E7D32) else Color(0xFFB0BEC5), CircleShape)
            )
        }
    )
}

@Composable
private fun FilterResultRow(
    item: NearbyParking,
    distanceMeters: Double?,
    onShowOnMap: () -> Unit
) {
    Surface(
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFFF8FAFF),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title.ifBlank { "Parking" }, fontWeight = FontWeight.SemiBold)
                val distLabel = distanceMeters?.let { d ->
                    if (d < 1000) "${d.roundToInt()} m" else String.format("%.1f km", d / 1000.0)
                } ?: "—"
                val cap = item.capacity?.let { "${item.available}/$it" } ?: "${item.available}"
                Text("Udaljenost: $distLabel", color = Color(0xFF546E7A))
                Text("Slobodno: $cap  •  ${item.pricePerHour} RSD/h", color = Color(0xFF546E7A))
                if (item.createdByEmail.isNotBlank()) {
                    Text("Autor: ${item.createdByEmail}", color = Color(0xFF607D8B), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                }
            }
            TextButton(onClick = onShowOnMap) { Text("Show on map") }
        }
    }
}

// ---------- Map ----------

@Composable
@Suppress("MissingPermission")
private fun MapboxMapView(
    modifier: Modifier = Modifier,
    onUserPoint: (Point) -> Unit,
    onParkingTapped: (NearbyParking) -> Unit,
    onDistanceUpdateForReserved: (Double) -> Unit,
    reservedSpot: NearbyParking?,
    filter: ParkingFilter,
    userPointForFilter: Point?,
    onParkingsChanged: (List<NearbyParking>) -> Unit,
    focusTarget: FocusTarget?,
    onUserMapGesture: () -> Unit,
    recenterSignal: Long?
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }

    val reservedSpotState by rememberUpdatedState(reservedSpot)
    val filterState by rememberUpdatedState(filter)
    val userPointState by rememberUpdatedState(userPointForFilter)

    val notificationChannelId = remember { "nearby_events" }
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
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }
    var lastSentMillis by remember { mutableStateOf(0L) }
    val minSendIntervalMs = 10_000L
    var parkings by remember { mutableStateOf(emptyList<NearbyParking>()) }
    val proximityMeters = 150.0
    val notifiedIds = remember { mutableStateMapOf<String, Long>() }
    var parkingsReg by remember { mutableStateOf<ListenerRegistration?>(null) }
    val authorEmailCache = remember { mutableStateMapOf<String, String>() }
    var trackingListener: OnIndicatorPositionChangedListener? by remember { mutableStateOf(null) }
    var positionListener: OnIndicatorPositionChangedListener? by remember { mutableStateOf(null) }
    var mapClickListener: OnMapClickListener? by remember { mutableStateOf(null) }
    var moveListener: OnMoveListener? by remember { mutableStateOf(null) }
    var followMe by remember { mutableStateOf(false) }
    val geofencingClient: GeofencingClient = remember { LocationServices.getGeofencingClient(context) }

    val geofencePendingIntent: PendingIntent = remember {
        val intent = Intent(context, ReservedGeofenceReceiver::class.java).apply {
            action = "com.stosic.parkup.home.GEOFENCE_RESERVED"
        }
        PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun updateReservedGeofence(res: NearbyParking?) {
        geofencingClient.removeGeofences(geofencePendingIntent)
        if (res == null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val geofence = Geofence.Builder()
            .setRequestId("${res.id}|${res.title}")
            .setCircularRegion(res.lat, res.lng, proximityMeters.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnFailureListener { }
    }

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

                        maybeNotifyReservedSpotNearby(
                            context = context,
                            nm = NotificationManagerCompat.from(context),
                            channelId = notificationChannelId,
                            hasNotifPermission = hasNotifPermission,
                            me = point,
                            reserved = reservedSpotState,
                            thresholdMeters = proximityMeters,
                            notifiedIds = notifiedIds
                        )

                        if (followMe) {
                            mapView?.getMapboxMap()?.setCamera(
                                CameraOptions.Builder()
                                    .center(point)
                                    .zoom(16.5)
                                    .build()
                            )
                        }
                    }
                    trackingListener?.let { location.addOnIndicatorPositionChangedListener(it) }

                    mapClickListener = OnMapClickListener { p: Point ->
                        val eff = parkings.filter { filterState.matches(it, userPointState ?: lastUserPoint) }
                        val nearest = eff.minByOrNull { distanceMeters(p.latitude(), p.longitude(), it.lat, it.lng) }
                        if (nearest != null) {
                            val d = distanceMeters(p.latitude(), p.longitude(), nearest.lat, nearest.lng)
                            if (d <= 50.0) {
                                onParkingTapped(nearest)
                                true
                            } else false
                        } else false
                    }
                    mapClickListener?.let { getMapboxMap().addOnMapClickListener(it) }

                    moveListener = object : OnMoveListener {
                        override fun onMoveBegin(detector: MoveGestureDetector) {
                            followMe = false
                            onUserMapGesture()
                        }
                        override fun onMove(detector: MoveGestureDetector): Boolean = false
                        override fun onMoveEnd(detector: MoveGestureDetector) {}
                    }
                    moveListener?.let { gestures.addOnMoveListener(it) }
                }
            }
        }
    )

    LaunchedEffect(focusTarget, mapView) {
        val ft = focusTarget ?: return@LaunchedEffect
        val mv = mapView ?: return@LaunchedEffect
        try {
            mv.getMapboxMap().setCamera(
                CameraOptions.Builder()
                    .center(Point.fromLngLat(ft.lng, ft.lat))
                    .zoom(ft.zoom)
                    .build()
            )
        } catch (_: Exception) {}
    }

    LaunchedEffect(recenterSignal, mapView, lastUserPoint) {
        val mv = mapView ?: return@LaunchedEffect
        val p = lastUserPoint ?: return@LaunchedEffect
        followMe = true
        try {
            mv.getMapboxMap().setCamera(
                CameraOptions.Builder()
                    .center(p)
                    .zoom(16.5)
                    .build()
            )
        } catch (_: Exception) {}
    }

    DisposableEffect(Unit) {
        parkingsReg = db.collection("parkings").addSnapshotListener { snap, _ ->
            val raw = snap?.documents?.mapNotNull { d ->
                val lat = d.getDouble("lat") ?: return@mapNotNull null
                val lng = d.getDouble("lng") ?: return@mapNotNull null
                val createdBy = d.getString("createdBy") ?: ""
                val createdByEmailField = d.getString("createdByEmail")

                NearbyParking(
                    id = d.id,
                    title = d.getString("title") ?: "Parking",
                    lat = lat,
                    lng = lng,
                    createdBy = createdBy,
                    createdByEmail = when {
                        !createdByEmailField.isNullOrBlank() -> createdByEmailField
                        createdBy.contains("@") -> createdBy
                        else -> authorEmailCache[createdBy] ?: ""
                    },
                    available = (d.getLong("availableSlots") ?: 0L),
                    capacity = d.getLong("capacity"),
                    pricePerHour = (d.getLong("pricePerHour") ?: 0L),
                    hasEv = d.getBoolean("hasEv") ?: false,
                    hasRamp = d.getBoolean("hasRamp") ?: false,
                    isCovered = d.getBoolean("isCovered") ?: false,
                    createdAt = (d.getLong("createdAt") ?: 0L)
                )
            } ?: emptyList()

            val missingUids = raw.map { it.createdBy }
                .filter { it.isNotBlank() && !it.contains("@") && authorEmailCache[it].isNullOrBlank() }
                .distinct()

            if (missingUids.isEmpty()) {
                parkings = raw
                onParkingsChanged(raw)
            } else {
                missingUids.forEach { u ->
                    db.collection("users").document(u).get()
                        .addOnSuccessListener { ud ->
                            val email = ud.getString("email") ?: ""
                            if (email.isNotBlank()) authorEmailCache[u] = email
                        }
                        .addOnCompleteListener {
                            val enriched = raw.map { p ->
                                if (p.createdByEmail.isBlank() && !p.createdBy.contains("@")) {
                                    p.copy(createdByEmail = authorEmailCache[p.createdBy] ?: "")
                                } else p
                            }
                            parkings = enriched
                            onParkingsChanged(enriched)
                        }
                }
                parkings = raw
                onParkingsChanged(raw)
            }
        }
        onDispose { parkingsReg?.remove(); parkingsReg = null }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { mapView?.onStart() }
            override fun onStop(owner: LifecycleOwner) { mapView?.onStop() }
            override fun onDestroy(owner: LifecycleOwner) {
                mapView?.onDestroy()
                parkingsReg?.remove(); parkingsReg = null
                try { trackingListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) } } catch (_: Exception) {}
                try { positionListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) } } catch (_: Exception) {}
                try { mapClickListener?.let { mapView?.getMapboxMap()?.removeOnMapClickListener(it) } } catch (_: Exception) {}
                try { moveListener?.let { mapView?.gestures?.removeOnMoveListener(it) } } catch (_: Exception) {}
                trackingListener = null
                positionListener = null
                mapClickListener = null
                moveListener = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try { trackingListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) } } catch (_: Exception) {}
            try { positionListener?.let { mapView?.location?.removeOnIndicatorPositionChangedListener(it) } } catch (_: Exception) {}
            try { mapClickListener?.let { mapView?.getMapboxMap()?.removeOnMapClickListener(it) } } catch (_: Exception) {}
            try { moveListener?.let { mapView?.gestures?.removeOnMoveListener(it) } } catch (_: Exception) {}
            trackingListener = null
            positionListener = null
            mapClickListener = null
            moveListener = null
        }
    }

    LaunchedEffect(reservedSpotState) {
        updateReservedGeofence(reservedSpotState)
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
        Text("Location permission is required to display your location on the map.")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRequest) { Text("Allow") }
            Button(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

private data class NearbyParking(
    val id: String,
    val title: String,
    val lat: Double,
    val lng: Double,
    val createdBy: String,
    val createdByEmail: String,
    val available: Long,
    val capacity: Long?,
    val pricePerHour: Long,
    val hasEv: Boolean,
    val hasRamp: Boolean,
    val isCovered: Boolean,
    val createdAt: Long
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

private fun maybeNotifyReservedSpotNearby(
    context: Context,
    nm: NotificationManagerCompat,
    channelId: String,
    hasNotifPermission: Boolean,
    me: Point,
    reserved: NearbyParking?,
    thresholdMeters: Double,
    notifiedIds: MutableMap<String, Long>,
) {
    if (!hasNotifPermission) return
    val now = System.currentTimeMillis()
    val r = reserved ?: return
    val dist = distanceMeters(me.latitude(), me.longitude(), r.lat, r.lng)
    if (dist <= thresholdMeters) {
        val key = "reserved:${r.id}"
        val last = notifiedIds[key] ?: 0L
        if (now - last >= 120_000L) {
            val title = "You’re close to your reserved spot"
            val text = "${r.title.ifBlank { "Parking" }} (~${dist.roundToInt()} m)"
            val notif = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return
            nm.notify(key.hashCode(), notif)
            notifiedIds[key] = now
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

class ReservedGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = com.google.android.gms.location.GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) return
        if (geofencingEvent.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val geofence = geofencingEvent.triggeringGeofences?.firstOrNull() ?: return
        val requestId = geofence.requestId // format: "<id>|<title>"
        val parts = requestId.split("|")
        val id = parts.getOrNull(0) ?: "reserved"
        val title = parts.getOrNull(1)?.ifBlank { "Parking" } ?: "Parking"

        val channelId = "nearby_events"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Blizina (ParkUp)",
                NotificationManager.IMPORTANCE_HIGH
            )
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return
        }

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Blizu si rezervisanog parkinga")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(("reserved:$id").hashCode(), notif)
    }
}