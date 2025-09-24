package com.stosic.parkup.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
    val trophyScale by animateFloatAsState(
        targetValue = if (animateTrophy) 1.15f else 1f,
        animationSpec = spring(stiffness = 400f),
        label = "trophy-press"
    )

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
                    IconButton(onClick = { showProfile = true }) {
                        Icon(imageVector = Icons.Filled.Person, contentDescription = "Profil", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF42A5F5))
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HomeScreen(
                userEmail = userEmail,
                onLogout = onLogout
            )
            FloatingActionButton(
                onClick = { },
                containerColor = Color(0xFF42A5F5),
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(imageVector = Icons.Filled.FilterList, contentDescription = "Filter")
            }
        }
    }
}
