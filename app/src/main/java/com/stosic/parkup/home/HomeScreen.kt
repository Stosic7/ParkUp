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

// --- DODATO: geofencing za notifikacije i kada je app ugašen ---
import android.content.BroadcastReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

// ---------- Filter model ----------

private data class ParkingFilter(
    val onlyAvailable: Boolean = false,
    val maxPricePerHour: Long? = null,
    val hasEv: Boolean = false,
    val hasRamp: Boolean = false,
    val isCovered: Boolean = false,
    var hasDisabledSpot : Boolean = false,
    val maxDistanceMeters: Double? = null,
    // Pretraga AUTORA po EMAIL adresi:
    val authorQuery: String = "",
    // Datum/vreme — podržava "yyyy-MM-dd" ili "yyyy-MM-dd HH:mm"
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
    // Autor preko EMAIL-a (case-insensitive)
    if (authorQuery.isNotBlank()) {
        val email = p.createdByEmail.lowercase()
        if (!email.contains(authorQuery.lowercase())) return false
    }
    return true
}

// ---------- Focus target for map ----------

private data class FocusTarget(
    val lat: Double,
    val lng: Double,
    val zoom: Double = 17.0,
    val nonce: Long = System.currentTimeMillis()
)

// ---------- Screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userEmail: String,
    onLogout: () -> Unit,
    onOverlayVisible: (Boolean) -> Unit = {}
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

    // Fleksibilni parser za datum/vreme
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
        // 1) osnovni filteri (dostupnost, cena, EV, rampa, natkriveno, distanca, autor-email)
        val base = allParkings.filter { filter.matches(it, lastUserPoint) }

        // 2) vremenski opseg (inclusive)
        val fromMillis = parseDateFlexible(filter.fromDate) ?: Long.MIN_VALUE
        // Ako "to" ima SAMO datum, produži do kraja dana; ako ima i vreme, koristi tačno
        val toRaw = parseDateFlexible(filter.toDate)
        val toMillisIncl = if (toRaw != null) {
            // utvrdi da li je korisnik uneo vreme (ima razmak)
            if (filter.toDate.contains(" ")) {
                toRaw // tačno vreme
            } else {
                toRaw + (24L * 60 * 60 * 1000 - 1) // do kraja dana
            }
        } else Long.MAX_VALUE

        base.filter { row ->
            val timeOk = row.createdAt in fromMillis..toMillisIncl
            timeOk
        }.sortedBy { p ->
            lastUserPoint?.let { distanceMeters(it.latitude(), it.longitude(), p.lat, p.lng) } ?: Double.MAX_VALUE
        }
    }

    // map focus
    var focusTarget by remember { mutableStateOf<FocusTarget?>(null) }

    // recenter UI
    var showRecenter by remember { mutableStateOf(false) }
    var recenterSignal by remember { mutableStateOf<Long?>(null) }

    val overlayVisible = (!showDetails && reserved == null)
    LaunchedEffect(showDetails, reserved) { onOverlayVisible(overlayVisible) }

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

        // RECENTER FAB
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(sp.title.ifBlank { "Parking" }, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { selected = null }) { Text("Zatvori") }
                    }

                    val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                    Text("Vreme: $now", color = Color(0xFF546E7A))
                    val cap = if (sp.capacity != null) "${sp.available}/${sp.capacity}" else "${sp.available}"
                    Text("Slobodno: $cap", color = Color(0xFF546E7A))
                    Spacer(Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { showDetails = true }, modifier = Modifier.weight(1f)) { Text("Detalji") }
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

        // Panel sa rezultatima (lista)
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
                        ) { Text("Nema parkinga za zadate filtere.") }
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
    var isCovered by remember { mutableStateOf(initial.isCovered) }
    var maxDistText by remember { mutableStateOf(initial.maxDistanceMeters?.roundToInt()?.toString().orEmpty()) }
    var authorText by remember { mutableStateOf(initial.authorQuery) }
    var fromDate by remember { mutableStateOf(initial.fromDate) }
    var toDate by remember { mutableStateOf(initial.toDate) }
    var hasDisabled by remember { mutableStateOf(initial.hasDisabledSpot) } // DODATO



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
            FilterChipSwitch("Invalidsko mesto", hasDisabled) { hasDisabled = it }
        }

        OutlinedTextField(
            value = maxDistText,
            onValueChange = { s -> if (s.all { it.isDigit() }) maxDistText = s },
            label = { Text("Max distanca (m)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = authorText,
            onValueChange = { authorText = it },
            label = { Text("Autor (email)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = fromDate,
                onValueChange = { fromDate = it },
                label = { Text("Od (yyyy-MM-dd ili yyyy-MM-dd HH:mm)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = toDate,
                onValueChange = { toDate = it },
                label = { Text("Do (yyyy-MM-dd ili yyyy-MM-dd HH:mm)") },
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
                        isCovered = isCovered,
                        hasDisabledSpot = hasDisabled,
                        maxDistanceMeters = maxDistText.toDoubleOrNull(),
                        authorQuery = authorText,
                        fromDate = fromDate.trim(),
                        toDate = toDate.trim()
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

    // Cache za email autora (uid -> email) da bi authorQuery radio i kada u parkings dokumentu nema email-a
    val authorEmailCache = remember { mutableStateMapOf<String, String>() }

    var trackingListener: OnIndicatorPositionChangedListener? by remember { mutableStateOf(null) }
    var positionListener: OnIndicatorPositionChangedListener? by remember { mutableStateOf(null) }
    var mapClickListener: OnMapClickListener? by remember { mutableStateOf(null) }
    var moveListener: OnMoveListener? by remember { mutableStateOf(null) }
    var followMe by remember { mutableStateOf(false) }

    // --- DODATO: GeofencingClient + PendingIntent za reserved spot ---
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
        // Uvek prvo ukloni stare geofencove za ovaj PI
        geofencingClient.removeGeofences(geofencePendingIntent)
        if (res == null) return
        // Zahteva ACCESS_FINE_LOCATION; za rad kada je app ugašen na Android 10+ preporučen ACCESS_BACKGROUND_LOCATION (manifest).
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
            .addOnFailureListener { /* ignoriši, nema rušenja UI-a */ }
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

                        // --- IZMENJENO: obaveštavaj SAMO za REZERVISANO mesto ---
                        maybeNotifyReservedSpotNearby(
                            context = context,
                            nm = NotificationManagerCompat.from(context),
                            channelId = notificationChannelId,
                            hasNotifPermission = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED,
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

    // recentriranje
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

    // Real-time parkings + obogaćivanje email-om autora
    DisposableEffect(Unit) {
        parkingsReg = db.collection("parkings").addSnapshotListener { snap, _ ->
            val raw = snap?.documents?.mapNotNull { d ->
                val lat = d.getDouble("lat") ?: return@mapNotNull null
                val lng = d.getDouble("lng") ?: return@mapNotNull null
                val createdBy = d.getString("createdBy") ?: "" // često UID
                val createdByEmailField = d.getString("createdByEmail") // ako već postoji u dokumentu

                NearbyParking(
                    id = d.id,
                    title = d.getString("title") ?: "Parking",
                    lat = lat,
                    lng = lng,
                    createdBy = createdBy,
                    createdByEmail = when {
                        !createdByEmailField.isNullOrBlank() -> createdByEmailField
                        createdBy.contains("@") -> createdBy // ako je već email
                        else -> authorEmailCache[createdBy] ?: "" // proba iz keša
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

            // Pronađi UIDs kojima fali email, pa dovuci iz users/{uid}.email (jednokratno po uid-u)
            val missingUids = raw.map { it.createdBy }
                .filter { it.isNotBlank() && !it.contains("@") && authorEmailCache[it].isNullOrBlank() }
                .distinct()

            if (missingUids.isEmpty()) {
                parkings = raw
                onParkingsChanged(raw)
            } else {
                // dovuci u pozadini; kad dobijemo, ažuriraj keš pa osveži listu
                missingUids.forEach { u ->
                    db.collection("users").document(u).get()
                        .addOnSuccessListener { ud ->
                            val email = ud.getString("email") ?: ""
                            if (email.isNotBlank()) authorEmailCache[u] = email
                        }
                        .addOnCompleteListener {
                            // Nakon završetka svih fetch-eva, rekonstruiši listu sa email-ovima iz keša
                            val enriched = raw.map { p ->
                                if (p.createdByEmail.isBlank() && !p.createdBy.contains("@")) {
                                    p.copy(createdByEmail = authorEmailCache[p.createdBy] ?: "")
                                } else p
                            }
                            parkings = enriched
                            onParkingsChanged(enriched)
                        }
                }
                // Privremeno prikaži i sirovu listu (da UI ne bude prazan)
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

    // --- DODATO: reaguj na promenu rezervacije i ažuriraj geofence ---
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
    // createdBy je obično UID
    val createdBy: String,
    // email autora (za filtriranje po autoru)
    val createdByEmail: String,
    val available: Long,
    val capacity: Long?,
    val pricePerHour: Long,
    val hasEv: Boolean,
    val hasRamp: Boolean,
    val isCovered: Boolean,
    // timestamp kreiranja (millis)
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

// --- NOVO: samo za REZERVISANO mesto, u foreground-u (dok je app aktivan) ---
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
            val title = "Blizu si rezervisanog parkinga"
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
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
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

// --- NOVO: BroadcastReceiver za geofence ENTER (radi i kada je app ugašen; treba manifest entry) ---
class ReservedGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // GeofencingClient šalje listu geofenci; mi koristimo prvi
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
