package com.stosic.parkup.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Add
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    userEmail: String,
    userData: Map<String, String>?, // 👈 dodato
    onLogout: () -> Unit
) {
    var showProfile by remember { mutableStateOf(false) }

    if (showProfile) {
        ProfileScreen(
            userEmail = userEmail,
            userData = userData, // 👈 prosleđeno
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

                FloatingActionButton(
                    onClick = { /* TODO: Dodaj novo parking mesto */ },
                    containerColor = Color(0xFF42A5F5),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Dodaj parking mesto"
                    )
                }

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
            }
        }
    }
}
