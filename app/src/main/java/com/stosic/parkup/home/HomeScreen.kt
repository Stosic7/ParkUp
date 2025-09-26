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

// ---------- Filter model ----------

private data class ParkingFilter(
    val onlyAvailable: Boolean = false,
    val maxPricePerHour: Long? = null,
    val hasEv: Boolean = false,
    val hasRamp: Boolean = false,
    val isCovered: Boolean = false,
    val maxDistanceMeters: Double? = null
) {
    val isActive: Boolean
        get() = onlyAvailable || maxPricePerHour != null || hasEv || hasRamp || isCovered || maxDistanceMeters != null
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
    return true
}

// ---------- Focus target for map ----------

private data class FocusTarget(
    val lat: Double,
    val lng: Double,
    val zoom: Double = 17.0,
    val nonce: Long = System.currentTimeMillis() // da bi LaunchedEffect reagovao i na isti lat/lng više puta
)

// ---------- Screen ----------

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

    // filter + list state
    var showFilter by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ParkingFilter()) }
    var showFilteredList by remember { mutableStateOf(false) }
    var allParkings by remember { mutableStateOf(emptyList<NearbyParking>()) }
    val filtered = remember(allParkings, filter, lastUserPoint) {
        allParkings.filter { filter.matches(it, lastUserPoint) }
            .sortedBy { p ->
                lastUserPoint?.let { distanceMeters(it.latitude(), it.longitude(), p.lat, p.lng) } ?: Double.MAX_VALUE
            }
    }

    // map focus
    var focusTarget by remember { mutableStateOf<FocusTarget?>(null) }

    // recenter UI
    var showRecenter by remember { mutableStateOf(false) }
    var recenterSignal by remember { mutableStateOf<Long?>(null) }

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
            // NOVO: signal da je korisnik ručno pomerio mapu
            onUserMapGesture = { showRecenter = true },
            // NOVO: signal za recentriranje kamere
            recenterSignal = recenterSignal
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

        // FILTER FAB (sa tačkicom kad je aktivan)
        AnimatedVisibility(
            visible = !showDetails,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 16.dp, bottom = 16.dp)
                .zIndex(20f)
        ) {
            Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.fillMaxSize()) {
                FloatingActionButton(onClick = { showFilter = true }) {
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

        // RECENTER FAB (iznad filter dugmeta)
        AnimatedVisibility(
            visible = showRecenter && !showDetails,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 16.dp, bottom = 88.dp) // iznad filter fab-a
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

        // Bubble pre detalja
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

                    // Header: naslov + ZATVORI
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(sp.title.ifBlank { "Parking" }, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { selected = null }) {
                            Text("Zatvori")
                        }
                    }

                    val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                    Text("Vreme: $now", color = Color(0xFF546E7A))
                    val cap = if (sp.capacity != null) "${sp.available}/${sp.capacity}" else "${sp.available}"
                    Text("Slobodno: $cap", color = Color(0xFF546E7A))
                    Spacer(Modifier.height(10.dp))

                    // Akcije
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { showDetails = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Detalji") }

                    }
                }
            }
        }

        // Baner nakon rezervacije
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

        // LEVI PANEL: lista filtriranih parkinga (ispod top bara)
        AnimatedVisibility(
            visible = showFilteredList && !showDetails,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(220)) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(180)) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 64.dp, start = 8.dp, end = 8.dp) // ~ispod top bara
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
                            "Rezultati (${filtered.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = { showFilteredList = false }) { Text("Zatvori") }
                    }
                    Divider()
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Nema parkinga za zadate filtere.")
                        }
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

    // Filter sheet
    if (showFilter) {
        ModalBottomSheet(onDismissRequest = { showFilter = false }) {
            FilterSheet(
                initial = filter,
                onApply = { new ->
                    filter = new
                    showFilter = false
                    showFilteredList = true // prikaži panel sa listom nakon primene
                },
                onReset = {
                    filter = ParkingFilter()
                    showFilter = false
                    showFilteredList = true
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
    var isCovered by remember { mutableStateOf(initial.isCovered) }
    var maxDistText by remember { mutableStateOf(initial.maxDistanceMeters?.roundToInt()?.toString().orEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Filteri", style = MaterialTheme.typography.titleMedium)
        Divider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = onlyAvailable, onCheckedChange = { onlyAvailable = it })
            Spacer(Modifier.width(8.dp))
            Text("Samo dostupni (available > 0)")
        }

        OutlinedTextField(
            value = maxPriceText,
            onValueChange = { s -> if (s.all { it.isDigit() }) maxPriceText = s },
            label = { Text("Max cena po satu (RSD)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChipSwitch("EV punjač", hasEv) { hasEv = it }
            FilterChipSwitch("Rampa", hasRamp) { hasRamp = it }
            FilterChipSwitch("Natkriveno", isCovered) { isCovered = it }
        }

        OutlinedTextField(
            value = maxDistText,
            onValueChange = { s -> if (s.all { it.isDigit() }) maxDistText = s },
            label = { Text("Max distanca (m)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) { Text("Reset") }
            Button(
                onClick = {
                    val new = ParkingFilter(
                        onlyAvailable = onlyAvailable,
                        maxPricePerHour = maxPriceText.toLongOrNull(),
                        hasEv = hasEv,
                        hasRamp = hasRamp,
                        isCovered = isCovered,
                        maxDistanceMeters = maxDistText.toDoubleOrNull()
                    )
                    onApply(new)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Primeni") }
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
            modifier = Modifier
                .padding(12.dp),
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
            }
            TextButton(onClick = onShowOnMap) { Text("Prikaži na mapi") }
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
    // NOVO:
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
    val lifecycleOwner = LocalLifecycleOwner.current
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
    var moveListener: OnMoveListener? by remember { mutableStateOf(null) }
    var followMe by remember { mutableStateOf(false) }

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

                        // notifikacije za filtrirane
                        val eff = parkings.filter { filterState.matches(it, userPointState ?: point) }
                        maybeNotifyNearbyParkings(
                            context, NotificationManagerCompat.from(context), notificationChannelId,
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                            point, eff, proximityMeters, notifiedIds
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

                    // NOVO: detekcija pomeranja mape (gesture) -> pokaži recenter dugme
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

    // fokus kamere kad se promeni focusTarget
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

    // NOVO: recentriranje na korisnika kada stigne signal
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
            onParkingsChanged(list) // <— gurni u HomeScreen za listu
        }
        onDispose {
            parkingsReg?.remove(); parkingsReg = null
        }
    }

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
                com.stosic.parkup.parking.map.MapboxParkingOverlay.cleanup()
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
