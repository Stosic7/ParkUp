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

// Parking filter model: holds all user-selected filter options and exposes isActive flag
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

// Filter predicate: returns true if a NearbyParking matches the current filter rules
private fun ParkingFilter.matches(p: NearbyParking, userPoint: Point?): Boolean {
    if (onlyAvailable && p.available <= 0L) return false
    if (maxPricePerHour != null && p.pricePerHour > maxPricePerHour) return false
    if (hasEv && !p.hasEv) return false
    if (hasRamp && !p.hasRamp) return false
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

// Camera focus request for Mapbox: where and how much to zoom, with a nonce to re-trigger effects
private data class FocusTarget(
    val lat: Double,
    val lng: Double,
    val zoom: Double = 17.0,
    val nonce: Long = System.currentTimeMillis()
)

// Home map screen: owns map UI state, filtering, selection, reservation and overlays
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userEmail: String,
    onLogout: () -> Unit,
    onOverlayVisible: (Boolean) -> Unit = {},
    onUiLockedChange: (Boolean) -> Unit = {}
) {
    // Core UI state: add dialog, selected item, details panel, reservation state, last user point, filters
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

    // Parse helper: accepts "yyyy-MM-dd" or "yyyy-MM-dd HH:mm" and returns epoch millis (or null)
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

    // Derived state: apply filter rules + optional date range + sort by distance from user
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

    // Map focus and recenter controls: target, whether to show recenter button, and a recenter signal
    var focusTarget by remember { mutableStateOf<FocusTarget?>(null) }
    var showRecenter by remember { mutableStateOf(false) }
    var recenterSignal by remember { mutableStateOf<Long?>(null) }

    // Report overlay and UI lock to parent (hide FABs/filters, disable top bar when reserved/details)
    val overlayVisible = (!showDetails && reserved == null)
    LaunchedEffect(showDetails, reserved) { onOverlayVisible(overlayVisible) }
    LaunchedEffect(reserved) { onUiLockedChange(reserved != null) }

    // Main content stack: Map view + FABs + transient panels (selection card, reserved banner, results list)
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

        // Add-parking FAB: visible only when no details/reservation are shown
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

        // Filter FAB (with small green dot when filters are active)
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

        // Recenter button: appears when user manually moves the map; recenters to current location
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

        // Bottom selection card: quick info and 'Details' action for the tapped parking
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

        // Top reservation banner: shows when a spot is reserved; includes distance and 'DETAILS' action
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

        // Floating filtered results panel: list of filtered parkings with 'Show on map' action// Floating filtered results panel: list of filtered parkings with 'Show on map' action
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

    // Add parking dialog: shown when FAB pressed; closes on dismiss/save
    if (showAdd) {
        AddParkingDialog(
            onDismiss = { showAdd = false },
            onSaved = { showAdd = false }
        )
    }

    // Parking details screen: handles reserve/cancel and triggers proximity notification when applicable
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

    // Filter bottom sheet: compose and apply/reset current ParkingFilter
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

// Filter editor sheet: controlled inputs for all filter fields, returns a ParkingFilter on Apply
@Composable
private fun FilterSheet(
    initial: ParkingFilter,
    onApply: (ParkingFilter) -> Unit,
    onReset: () -> Unit
) {
    // Local state mirrors: text/booleans bound to inputs; initialized from 'initial'
    var onlyAvailable by remember { mutableStateOf(initial.onlyAvailable) }
    var maxPriceText by remember { mutableStateOf(initial.maxPricePerHour?.toString().orEmpty()) }
    var hasEv by remember { mutableStateOf(initial.hasEv) }
    var hasRamp by remember { mutableStateOf(initial.hasRamp) }
    var maxDistText by remember { mutableStateOf(initial.maxDistanceMeters?.roundToInt()?.toString().orEmpty()) }
    var authorText by remember { mutableStateOf(initial.authorQuery) }
    var fromDate by remember { mutableStateOf(initial.fromDate) }
    var toDate by remember { mutableStateOf(initial.toDate) }

    // Layout with inputs: toggles, numeric fields, author email, and date range
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

        // Actions: Reset to defaults or Apply to return a new ParkingFilter to caller
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

// Tiny toggle chip used in FilterSheet: colored dot indicates ON/OFF
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

// One row in the filtered results list: shows summary + 'Show on map' action
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
                Text("Distance: $distLabel", color = Color(0xFF546E7A))
                Text("Available: $cap  •  ${item.pricePerHour} RSD/h", color = Color(0xFF546E7A))
                if (item.createdByEmail.isNotBlank()) {
                    Text("Author: ${item.createdByEmail}", color = Color(0xFF607D8B), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                }
            }
            TextButton(onClick = onShowOnMap) { Text("Show on map") }
        }
    }
}

// ---------- Map ----------

// Mapbox wrapper: manages permissions, MapView lifecycle, listeners, geofencing and proximity notifications
@Composable
@Suppress("MissingPermission")
private fun MapboxMapView(
    modifier: Modifier = Modifier,
    onUserPoint: (Point) -> Unit, // send the parent (HomeScreen) the user's current location.
    onParkingTapped: (NearbyParking) -> Unit, // when you click on the map, you find the closest parking lot to the clicked point (≤50m) and report it above.
    onDistanceUpdateForReserved: (Double) -> Unit, // constantly calculate the distance user <-> reserved parking
    reservedSpot: NearbyParking?, // if a place is reserved, the Mapbox part knows how to track the distance and set a geofence.
    filter: ParkingFilter,
    userPointForFilter: Point?,
    onParkingsChanged: (List<NearbyParking>) -> Unit, // when the firestore snapshot arrives, upload the whole list
    focusTarget: FocusTarget?, // when someone clicks "Show on map" in the list, tell the map where to go.
    onUserMapGesture: () -> Unit, // user moved the map, show “Recenter”.
    recenterSignal: Long? // ping from parent to recenter and follow user.
) {
    // Permission and notification setup: location + (Android 13+) POST_NOTIFICATIONS + channel creation
    val context = LocalContext.current
    val activity = context as? Activity
    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) } // catching location state from the OS
    val reservedSpotState by rememberUpdatedState(reservedSpot)
    val filterState by rememberUpdatedState(filter)
    val userPointState by rememberUpdatedState(userPointForFilter)
    val notificationChannelId = remember { "nearby_events" }
    val wantNotifPermission = true
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
        val ch = NotificationChannel(
            notificationChannelId,
            "Blizina (ParkUp)",
            NotificationManager.IMPORTANCE_HIGH
        )
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
        if (wantNotifPermission && !hasNotifPermission) {
            notifPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Runtime location permission request flow (fine/coarse); show rationale screen if denied
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

    // Firebase and Map state: map view, user point, rate-limited location writes, parking list and listeners
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid
    var mapView: MapView? by remember { mutableStateOf(null) } // reference to real Android MapView
    var lastUserPoint by remember { mutableStateOf<Point?>(null) } // last known user location
    var lastSentMillis by remember { mutableStateOf(0L) } // send location
    val minSendIntervalMs = 10_000L // max 10s
    var parkings by remember { mutableStateOf(emptyList<NearbyParking>()) } // list of parkings from firestore
    val proximityMeters = 150.0 // proximity meters for notification
    val notifiedIds = remember { mutableStateMapOf<String, Long>() }
    var parkingsReg by remember { mutableStateOf<ListenerRegistration?>(null) } // snapshot from firestore
    val authorEmailCache = remember { mutableStateMapOf<String, String>() }
    var trackingListener: OnIndicatorPositionChangedListener? by remember { mutableStateOf(null) } // mapbox listener
    var positionListener: OnIndicatorPositionChangedListener? by remember { mutableStateOf(null) } // mapbox listener
    var mapClickListener: OnMapClickListener? by remember { mutableStateOf(null) } // mapbox listener
    var moveListener: OnMoveListener? by remember { mutableStateOf(null) } // mapbox listener
    var followMe by remember { mutableStateOf(false) } // lock the camera to the target
    val geofencingClient: GeofencingClient = remember { LocationServices.getGeofencingClient(context) } // for reserved parking

    // PendingIntent for geofence events: broadcast received by ReservedGeofenceReceiver
    val geofencePendingIntent: PendingIntent = remember {
        val intent = Intent(context, ReservedGeofenceReceiver::class.java).apply {
            action = "com.stosic.parkup.home.GEOFENCE_RESERVED"
        }
        PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            (PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // Create/replace geofence for the reserved spot; triggers on ENTER within proximityMeters
    fun updateReservedGeofence(res: NearbyParking?) {
        geofencingClient.removeGeofences(geofencePendingIntent)
        // if we have FINE location and reserved parking, create geofence
        if (res == null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        // create Geofence (requestId = “id|title”, radius = 150m, trigger = ENTER).
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

        // When the user enters the circle, the ReservedGeofenceReceiver receives an event and sends a local notification.
        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnFailureListener { }
    }

    // AndroidView bridging Mapbox MapView into Compose; sets style and installs listeners
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                mapView = this
                getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) {
                    com.stosic.parkup.parking.map.MapboxParkingOverlay.install(this) // setup parking pins
                    if (hasLocationPermission(context)) {
                        // if location is enabled, show blue dot that represents a user on the map
                        try {
                            location.updateSettings {
                                enabled = true
                                pulsingEnabled = true
                            }
                        } catch (_: SecurityException) { }
                    }

                    // One-shot recenter on first location fix: center camera and remove the temporary listener
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

                    // Continuous tracking listener: sends location (rate-limited), updates reserved distance, optional 'follow me'
                    trackingListener = OnIndicatorPositionChangedListener { point: Point ->
                        lastUserPoint = point
                        onUserPoint(point)
                        // sending user's location max every 10s
                        maybeSendLocation(db, uid, point, lastSentMillis, minSendIntervalMs) {
                            lastSentMillis = it
                        }
                        // if there are reserved spots, calculate distance and callback
                        reservedSpotState?.let { rs ->
                            val d = distanceMeters(point.latitude(), point.longitude(), rs.lat, rs.lng)
                            onDistanceUpdateForReserved(d)
                        }

                        // local notification is the user is nearby reserved parking spot
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

                        // move camera to the user and lock it in
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

                    // Map click: find nearest filtered parking within 50m of click and select it
                    mapClickListener = OnMapClickListener { p: Point ->
                        // On the click of the map, take the filtered parking lots (filter + userPoint) and find the nearest parking lot to the clicked point.
                        val eff = parkings.filter { filterState.matches(it, userPointState ?: lastUserPoint) }
                        val nearest = eff.minByOrNull { distanceMeters(p.latitude(), p.longitude(), it.lat, it.lng) }
                        if (nearest != null) {
                            val d = distanceMeters(p.latitude(), p.longitude(), nearest.lat, nearest.lng)
                            if (d <= 50.0) {
                                // if the nearest one is ≤ 50 m from the click onParkingTapped(nearest), open the bottom card.
                                onParkingTapped(nearest)
                                true
                            } else false
                        } else false
                    }
                    mapClickListener?.let { getMapboxMap().addOnMapClickListener(it) }

                    // User move gestures: disable 'follow me' and tell parent to show recenter button
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

    // Focus camera to a requested target (from list 'Show on map')
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


    // Recenter camera to last known user location and enable 'follow me'
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


    // Firestore realtime subscription: load/patch 'parkings' and resolve author emails as needed
    DisposableEffect(Unit) {
        // catching "parkings" from firebase and mapping them into "NearbyParking"
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
                    //If the createdByEmail field does not exist but createdBy looks like an email, use it
                    createdByEmail = when {
                        !createdByEmailField.isNullOrBlank() -> createdByEmailField
                        createdBy.contains("@") -> createdBy
                        // try to cache createdby it into an email
                        else -> authorEmailCache[createdBy] ?: ""
                    },
                    available = (d.getLong("availableSlots") ?: 0L),
                    capacity = d.getLong("capacity"),
                    pricePerHour = (d.getLong("pricePerHour") ?: 0L),
                    hasEv = d.getBoolean("hasEv") ?: false,
                    hasRamp = d.getBoolean("hasRamp") ?: false,
                    createdAt = (d.getLong("createdAt") ?: 0L)
                )
            } ?: emptyList()

            // If there are "missing UIDs", add createdByEmail asynchronously,
            // then push the enriched result to parkings and onParkingsChanged.
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

    // Tie MapView lifecycle to the Compose lifecycle: start/stop/destroy + remove all listeners safely
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

    // Keep geofence in sync when reservation changes
    LaunchedEffect(reservedSpotState) {
        updateReservedGeofence(reservedSpotState)
    }
}

// Utility: check if app currently has any location permission granted
private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

// Simple permission rationale UI with 'Allow' and 'Settings' actions
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

// Domain model for a parking item used on the map and in lists
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
    val createdAt: Long
)

// Rate-limited location write to Firestore (locations/{uid}); calls onSent when stored
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

// Local notification when user enters threshold distance to the reserved spot; deduped by notifiedIds
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
        // creating unique key for that reservation
        val key = "reserved:${r.id}"
        // the time when a notification was last sent for that key; if there is no record, take 0 (never).
        val last = notifiedIds[key] ?: 0L
        // checking whether at least 120 seconds have passed since the last notification for that reservation.
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
            if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
            ) return
            nm.notify(key.hashCode(), notif)
            notifiedIds[key] = now
        }
    }
}

// Haversine distance (meters) between two lat/lon points
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

// BroadcastReceiver for geofence ENTER: shows a high-priority local notification when geofence is triggered
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
        val ch = NotificationChannel(
            channelId,
            "Blizina (ParkUp)",
            NotificationManager.IMPORTANCE_HIGH
        )
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)

        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

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