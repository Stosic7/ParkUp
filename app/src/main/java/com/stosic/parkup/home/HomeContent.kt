package com.stosic.parkup.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.stosic.parkup.R
import com.stosic.parkup.leaderboard.LeaderboardScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    userEmail: String,
    userData: Map<String, String>?,
    onLogout: () -> Unit
) {
    // Local UI state: profile/leaderboard visibility, small trophy animation, UI lock, and animation state
    var showProfile by remember { mutableStateOf(false) }
    var showLeaderboard by remember { mutableStateOf(false) }
    var animateTrophy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var uiLocked by rememberSaveable { mutableStateOf(false) }
    val trophyScale by animateFloatAsState(
        targetValue = if (animateTrophy) 1.15f else 1f,
        animationSpec = spring(stiffness = 400f),
        label = "trophy-press"
    )

    // Firebase handles and search radius state (persisted per user)
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid
    var radiusEnabled by rememberSaveable { mutableStateOf(false) }
    var radiusMeters by rememberSaveable { mutableStateOf(500f) }
    var showRadiusBar by rememberSaveable { mutableStateOf(true) }

    // On first load of a logged-in user: read saved searchRadius from Firestore and apply to UI
    LaunchedEffect(uid) {
        if (uid != null) {
            db.collection("users").document(uid).get().addOnSuccessListener { d ->
                val r = d.getLong("searchRadius")?.toInt() ?: 0
                if (r > 0) {
                    radiusEnabled = true
                    radiusMeters = r.toFloat()
                }
            }
        }
    }

    // Persist search radius whenever the switch/slider changes (0 when disabled; clamped for safety)
    LaunchedEffect(uid) {
        if (uid != null) {
            db.collection("users").document(uid).get().addOnSuccessListener { d ->
                val r = d.getLong("searchRadius")?.toInt() ?: 0
                if (r > 0) {
                    radiusEnabled = true
                    radiusMeters = r.toFloat()
                }
            }
        }
    }

    LaunchedEffect(radiusEnabled, radiusMeters, uid) {
        if (uid != null) {
            val value = if (radiusEnabled) radiusMeters.toInt().coerceIn(50, 5000) else 0
            db.collection("users").document(uid).update(mapOf("searchRadius" to value))
        }
    }

    // Route to Leaderboard screen immediately when requested (and stop composing the rest of Home)
    if (showLeaderboard) {
        LeaderboardScreen(onBack = { showLeaderboard = false })
        return
    }

    // Route to Profile screen immediately when requested (and stop composing the rest of Home)
    if (showProfile) {
        ProfileScreen(
            userEmail = userEmail,
            userData = userData,
            onBack = { showProfile = false },
            onLogout = onLogout,
            onOpenLeaderboard = { showProfile = false; showLeaderboard = true }
        )
        return
    }

    // Controls the bottom-sheet that shows the "all spots" table
    var showAllTable by remember { mutableStateOf(false) }

    // Lightweight row model for the "all spots" table
    data class AllSpotRow(
        val id: String,
        val title: String,
        val pricePerHour: Long?,
        val capacity: Long?,
        val availableSlots: Long?,
        val placeType: String?,
        val zone: String?
    )

    // Live data and listener registration for "parkings" collection shown in the bottom-sheet
    var allSpots by remember { mutableStateOf<List<AllSpotRow>>(emptyList()) }
    var allSpotsReg by remember { mutableStateOf<ListenerRegistration?>(null) }

    // Start/stop Firestore realtime listener only while the table sheet is open (saves resources)
    LaunchedEffect(showAllTable) {
        if (showAllTable) {
            allSpotsReg = db.collection("parkings")
                .addSnapshotListener { qs, _ ->
                    if (qs != null) {
                        allSpots = qs.documents.map { doc ->
                            AllSpotRow(
                                id = doc.id,
                                title = doc.getString("title") ?: "-",
                                pricePerHour = doc.getLong("pricePerHour"),
                                capacity = doc.getLong("capacity"),
                                availableSlots = doc.getLong("availableSlots"),
                                placeType = doc.getString("placeType"),
                                zone = doc.getString("zone")
                            )
                        }
                    }
                }
        } else {
            allSpotsReg?.remove()
            allSpotsReg = null
        }
    }

    // Tiny vertical separator composable used in the table header/body
    @Composable
    fun VSep(color: Color) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(color)
        )
    }

    // Reusable grid line color for the table (slightly transparent outlineVariant)
    val gridLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    // Scaffold with a custom top bar (leaderboard, table, profile) and main content area
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            enabled = !uiLocked,
                            onClick = {
                                scope.launch {
                                    animateTrophy = true
                                    delay(120)
                                    animateTrophy = false
                                }
                                showLeaderboard = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.EmojiEvents,
                                contentDescription = "Rang lista",
                                tint = Color.White,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = trophyScale
                                    scaleY = trophyScale
                                }
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(enabled = !uiLocked, onClick = { showAllTable = true }) {
                            Icon(
                                imageVector = Icons.Filled.TableRows,
                                contentDescription = "Tabela svih",
                                tint = Color.White
                            )
                        }
                    }
                },
                title = {
                    Image(
                        painter = painterResource(id = R.drawable.logo1),
                        contentDescription = "ParkUp",
                        modifier = Modifier.height(28.dp),
                        contentScale = ContentScale.Fit
                    )
                },
                actions = {
                    IconButton(enabled = !uiLocked, onClick = { showProfile = true }) {
                        Icon(imageVector = Icons.Filled.Person, contentDescription = "Profil", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF42A5F5))
            )
        }
    ) { padding ->
        // Main column under the top bar; hosts the radius controls and the HomeScreen content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Radius bar
            // Animated search radius panel: switch to enable, slider to choose distance (with enter/exit animations)
            AnimatedVisibility(
                visible = showRadiusBar,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Search radius", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = radiusEnabled, onCheckedChange = { radiusEnabled = it })
                    }
                    if (radiusEnabled) {
                        Text("Radius: ${radiusMeters.toInt()} m", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = radiusMeters,
                            onValueChange = { radiusMeters = it },
                            valueRange = 100f..2000f,
                            steps = 18
                        )
                    }
                }
            }

            // HomeScreen content wrapper; passes callbacks to hide the radius bar and lock UI when needed
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                HomeScreen(
                    userEmail = userEmail,
                    onLogout = onLogout,
                    onOverlayVisible = { show -> showRadiusBar = show },
                    onUiLockedChange = { locked -> uiLocked = locked }
                )
            }
        }
    }

    // Bottom sheet with a realtime table of all parking spots (only shown when requested)
    if (showAllTable) {
        ModalBottomSheet(
            onDismissRequest = { showAllTable = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            // Header title for the sheet
            Text(
                "All registered parking spots",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            // Table header row: column titles with a colored background and separators
            Column {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary)) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .height(IntrinsicSize.Min)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val headColor = MaterialTheme.colorScheme.onPrimary
                        Text("Name", Modifier.weight(1.8f).padding(vertical = 10.dp), color = headColor, fontWeight = FontWeight.SemiBold)
                        VSep(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f))
                        Text("Price",  Modifier.weight(0.8f).padding(start = 8.dp), color = headColor, fontWeight = FontWeight.SemiBold)
                        VSep(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f))
                        Text("Cap.",  Modifier.weight(0.8f).padding(start = 8.dp), color = headColor, fontWeight = FontWeight.SemiBold)
                        VSep(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f))
                        Text("Av.", Modifier.weight(0.8f).padding(start = 8.dp), color = headColor, fontWeight = FontWeight.SemiBold)
                        VSep(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f))
                        Text("Type",   Modifier.weight(0.9f).padding(start = 8.dp), color = headColor, fontWeight = FontWeight.SemiBold)
                        VSep(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f))
                        Text("Zone",  Modifier.weight(0.9f).padding(start = 8.dp), color = headColor, fontWeight = FontWeight.SemiBold)
                    }
                }
                Divider(color = gridLine)

                // Table body: realtime rows from Firestore; zebra background and grid separators
                LazyColumn {
                    items(allSpots, key = { it.id }) { spot ->
                        val bg = if (allSpots.indexOf(spot) % 2 == 0)
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f)
                        else Color.Transparent

                        Box(Modifier.background(bg)) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .height(IntrinsicSize.Min)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(spot.title, Modifier.weight(1.8f).padding(vertical = 10.dp))
                                VSep(gridLine)
                                Text(spot.pricePerHour?.let { "${it} RSD/h" } ?: "-", Modifier.weight(0.8f).padding(start = 8.dp))
                                VSep(gridLine)
                                Text((spot.capacity ?: 0L).toString(), Modifier.weight(0.8f).padding(start = 8.dp))
                                VSep(gridLine)
                                Text((spot.availableSlots ?: 0L).toString(), Modifier.weight(0.8f).padding(start = 8.dp))
                                VSep(gridLine)
                                Text(spot.placeType.orEmpty(), Modifier.weight(0.9f).padding(start = 8.dp))
                                VSep(gridLine)
                                Text(spot.zone.orEmpty(), Modifier.weight(0.9f).padding(start = 8.dp))
                            }
                        }
                        Divider(color = gridLine)
                    }
                }
            }
        }
    }
}
