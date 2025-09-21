package com.stosic.parkup.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    userEmail: String,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TabRow(selectedTabIndex = 0) {
                Tab(selected = true, onClick = { }) {
                    Text("Rang lista")
                }
                Tab(selected = false, onClick = { }) {
                    Text("Profil")
                }
                Tab(selected = false, onClick = { }) {
                    Text("Info")
                }
            }
        },
        bottomBar = {
            BottomAppBar {
                Text(
                    "Dodatne opcije",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            HomeScreen(
                userEmail = userEmail,
                onLogout = onLogout
            )
        }
    }
}
