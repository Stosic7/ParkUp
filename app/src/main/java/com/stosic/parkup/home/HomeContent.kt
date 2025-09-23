package com.stosic.parkup.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stosic.parkup.parking.ui.AddParkingFab
import com.stosic.parkup.parking.ui.AddParkingDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    userEmail: String,
    userData: Map<String, String>?,
    onLogout: () -> Unit
) {
    var showProfile by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showProfile) {
        ProfileScreen(
            userEmail = userEmail,
            userData = userData,
            onBack = { showProfile = false },
            onLogout = onLogout
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { /* TODO: rang lista */ }) {
                            Icon(
                                imageVector = Icons.Filled.EmojiEvents,
                                contentDescription = "Rang lista",
                                tint = Color.White
                            )
                        }
                    },
                    title = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "ParkUp",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontStyle = FontStyle.Italic,
                                color = Color.White
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showProfile = true }) {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = "Profil",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF42A5F5)
                    )
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

                AddParkingFab(
                    onClicked = { showAddDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                )

                FloatingActionButton(
                    onClick = { /* TODO: Filter opcije */ },
                    containerColor = Color(0xFF42A5F5),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = "Filter"
                    )
                }

                if (showAddDialog) {
                    AddParkingDialog(
                        onDismiss = { showAddDialog = false },
                        onSaved = { showAddDialog = false /* TODO: popup "Parking dodat (+10p)" */ }
                    )
                }
            }
        }
    }
}
