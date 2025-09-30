// FILE 2/2: HomeContent.kt
package com.stosic.parkup.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stosic.parkup.leaderboard.LeaderboardScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- DODATO: Firebase za čuvanje radijusa
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    userEmail: String,
    userData: Map<String, String>?,
    onLogout: () -> Unit
) {
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

    // Firebase
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var radiusEnabled by rememberSaveable { mutableStateOf(false) }
    var radiusMeters by rememberSaveable { mutableStateOf(500f) }

    // NOVO: vidljivost radius bara koju diktira HomeScreen (vidljivo ako: nema detalja i nema rezervacije)
    var showRadiusBar by rememberSaveable { mutableStateOf(true) }

    // Učitaj postojeću vrednost iz users/{uid}.searchRadius
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

    // Svaka promena – upiši u Firestore (0 = isključeno)
    LaunchedEffect(radiusEnabled, radiusMeters, uid) {
        if (uid != null) {
            val value = if (radiusEnabled) radiusMeters.toInt().coerceIn(50, 5000) else 0
            db.collection("users").document(uid).update(mapOf("searchRadius" to value))
        }
    }

    if (showLeaderboard) {
        LeaderboardScreen(onBack = { showLeaderboard = false })
        return
    }
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
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
                },
                title = {
                    Text(
                        "ParkUp",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontStyle = FontStyle.Italic
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // NOVO: Radius bar je animiran i veže se za showRadiusBar koji stiže iz HomeScreen
            AnimatedVisibility(
                visible = showRadiusBar,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pretraga u radijusu", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = radiusEnabled, onCheckedChange = { radiusEnabled = it })
                    }
                    if (radiusEnabled) {
                        Text("Radijus: ${radiusMeters.toInt()} m", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = radiusMeters,
                            onValueChange = { radiusMeters = it },
                            valueRange = 100f..2000f,
                            steps = 18
                        )
                    }
                }
            }

            // Ekran sa mapom i ostalim – sada prosleđujemo callback da kontroliše vidljivost radius bara
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
}
