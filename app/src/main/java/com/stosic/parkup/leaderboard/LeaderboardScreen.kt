package com.stosic.parkup.leaderboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stosic.parkup.leaderboard.model.LB_COMPARATOR
import com.stosic.parkup.leaderboard.model.docToLbEntry
import com.google.firebase.firestore.FirebaseFirestore
import com.stosic.parkup.leaderboard.model.LbEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(
    onBack: () -> Unit
) {
    // Firestore handle + UI state for leaderboard entries and loading flag
    val db = remember { FirebaseFirestore.getInstance() }
    var entries by remember { mutableStateOf<List<LbEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Live-listen 'users' collection; map docs -> LbEntry, sort by LB_COMPARATOR, stop listening on dispose
    DisposableEffect(Unit) {
        val reg = db.collection("users")
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.map { d -> docToLbEntry(d) }.orEmpty()

                entries = list.sortedWith(LB_COMPARATOR)

                loading = false
            }
        onDispose { reg.remove() }
    }

    // Precompute: count duplicate display names (for disambiguation) and take top 3 for podium
    val nameCounts = remember(entries) { entries.groupingBy { it.fullName }.eachCount() }
    val podium = remember(entries) { entries.take(3) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Nazad", tint = Color.White)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFFD54F))
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        Text("Leaderboard", color = Color.White, fontWeight = FontWeight.SemiBold,
                            fontStyle = FontStyle.Italic)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF42A5F5))
            )
        },
        containerColor = Color(0xFFF0F4F8)
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp, start = 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PodiumCup(name = podium.getOrNull(1)?.fullName.orEmpty(), rank = 2)
                        androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
                        PodiumCup(name = podium.getOrNull(0)?.fullName.orEmpty(), rank = 1, big = true)
                        androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
                        PodiumCup(name = podium.getOrNull(2)?.fullName.orEmpty(), rank = 3)
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        "All users",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                    Divider()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        itemsIndexed(entries, key = { _, e -> e.uid }) { idx, e ->
                            val display = if ((nameCounts[e.fullName] ?: 0) > 1)
                                "${e.fullName} (${e.email})"
                            else e.fullName
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "#${idx + 1}",
                                    modifier = Modifier.width(48.dp),
                                    fontWeight = if (idx < 3) FontWeight.Bold else FontWeight.Medium,
                                    color = if (idx < 3) Color(0xFF1E88E5) else Color(0xFF455A64)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        display,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (idx < 3) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(
                                        e.email,
                                        fontSize = 12.sp,
                                        color = Color(0xFF78909C),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text("${e.points} points", fontWeight = FontWeight.Medium)
                            }
                            if (idx < entries.lastIndex) Divider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodiumCup(name: String, rank: Int, big: Boolean = false) {
    val tint = when (rank) {
        1 -> Color(0xFFFFD54F)
        2 -> Color(0xFFB0BEC5)
        else -> Color(0xFFFFB74D)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (big) 88.dp else 72.dp)
        )
        Text(
            text = if (name.isBlank()) "-" else name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (rank == 1) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
