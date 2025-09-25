package com.stosic.parkup.parking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.stosic.parkup.parking.data.ParkingActions
import com.stosic.parkup.parking.data.ParkingComment
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParkingDetailsScreen(
    parkingId: String,
    parkingTitle: String,
    onBack: () -> Unit,
    onReserved: () -> Unit,
    onCanceled: () -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val uid = remember { FirebaseAuth.getInstance().currentUser?.uid }

    var avgRating by remember { mutableStateOf(0.0) }
    var myRating by remember { mutableStateOf(0) }
    var comments by remember { mutableStateOf<List<ParkingComment>>(emptyList()) }
    var capacity by remember { mutableStateOf<Long?>(null) }
    var available by remember { mutableStateOf<Long?>(null) }

    var pricePerHour by remember { mutableStateOf<Long?>(null) }
    var hasEv by remember { mutableStateOf(false) }
    var hasRamp by remember { mutableStateOf(false) }
    var isCovered by remember { mutableStateOf(false) }
    var createdBy by remember { mutableStateOf<String?>(null) }
    val isCreator = remember(uid, createdBy) { uid != null && uid == createdBy }

    var hasActiveReservation by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Učitavanje osnovnih polja parkinga + moja ocena
    LaunchedEffect(parkingId) {
        val doc = db.collection("parkings").document(parkingId).get().await()
        capacity = doc.getLong("capacity")
        available = doc.getLong("availableSlots")
        pricePerHour = doc.getLong("pricePerHour")
        hasEv = doc.getBoolean("hasEv") ?: false
        hasRamp = doc.getBoolean("hasRamp") ?: false
        isCovered = doc.getBoolean("isCovered") ?: false
        createdBy = doc.getString("createdBy")

        ParkingActions.getAverageRating(parkingId).onSuccess { avgRating = it }
        if (uid != null) {
            val rd = db.collection("parkings").document(parkingId)
                .collection("ratings").document(uid).get().await()
            myRating = (rd.getLong("stars") ?: 0L).toInt()
        }
    }

    // Live komentari
    DisposableEffect(parkingId) {
        val reg = db.collection("parkings").document(parkingId)
            .collection("comments")
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(ParkingComment::class.java) }.orEmpty()
                comments = list.sortedByDescending { it.createdAt }
            }
        onDispose { reg.remove() }
    }

    // Live status rezervacije za ovog korisnika
    DisposableEffect(parkingId, uid) {
        var resReg: ListenerRegistration? = null
        if (uid != null) {
            resReg = db.collection("users").document(uid)
                .collection("reservations").document(parkingId)
                .addSnapshotListener { snap, _ ->
                    val st = snap?.getString("status") ?: ""
                    hasActiveReservation = (st == "active")
                }
        }
        onDispose { resReg?.remove() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Nazad", tint = Color.White)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocalParking, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(parkingTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF42A5F5))
            )
        },
        // bottom bar: Rezerviši ili Otkaži
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (!hasActiveReservation) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true; error = null
                                val r = ParkingActions.reserveSpot(parkingId)
                                busy = false
                                if (r.isSuccess) onReserved() else error = r.exceptionOrNull()?.message ?: "Greška."
                            }
                        },
                        enabled = !busy && (available ?: 0L) > 0L,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5E35B1),
                            contentColor = Color.White
                        )
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Rezerviši mesto")
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true; error = null
                                val r = ParkingActions.finishReservation(parkingId)
                                busy = false
                                if (r.isSuccess) onCanceled() else error = r.exceptionOrNull()?.message ?: "Greška pri otkazivanju."
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD32F2F),
                            contentColor = Color.White
                        )
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Otkaži rezervaciju")
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = Color(0xFFB71C1C))
                }
            }
        },
        containerColor = Color(0xFFF6F8FB)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    parkingTitle,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(onClick = {}, label = { Text("Prosek ${"%.1f".format(avgRating)}") })
            }

            val cap = capacity; val avail = available
            if (cap != null && avail != null) {
                SuggestionChip(onClick = {}, label = { Text("Kapacitet: $avail/$cap") })
            }

            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 1.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Wallet, contentDescription = null)
                        Text("Cena po satu:")
                        Spacer(Modifier.width(8.dp))
                        if (isCreator) {
                            var field by remember(pricePerHour) { mutableStateOf((pricePerHour ?: 0L).toString()) }
                            OutlinedTextField(
                                value = field,
                                onValueChange = { value -> if (value.all { it.isDigit() }) field = value },
                                modifier = Modifier.width(120.dp),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    val v = field.toLongOrNull() ?: 0L
                                    pricePerHour = v
                                    // fire & forget
                                    scope.launch {
                                        db.collection("parkings").document(parkingId).update("pricePerHour", v)
                                    }
                                }
                            ) { Text("Sačuvaj") }
                        } else {
                            Text("${pricePerHour ?: 0} RSD/h", fontWeight = FontWeight.Medium)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LabeledSwitch("EV punjač", hasEv, isCreator) { new ->
                            hasEv = new
                            scope.launch {
                                db.collection("parkings").document(parkingId).update("hasEv", new)
                            }
                        }
                        LabeledSwitch("Rampa", hasRamp, isCreator) { new ->
                            hasRamp = new
                            scope.launch {
                                db.collection("parkings").document(parkingId).update("hasRamp", new)
                            }
                        }
                        LabeledSwitch("Natkriveno", isCovered, isCreator) { new ->
                            isCovered = new
                            scope.launch {
                                db.collection("parkings").document(parkingId).update("isCovered", new)
                            }
                        }
                    }
                }
            }

            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RatingBar(
                        current = myRating,
                        onSet = { stars ->
                            myRating = stars
                            scope.launch {
                                ParkingActions.setRating(parkingId, stars)
                                ParkingActions.getAverageRating(parkingId).onSuccess { avgRating = it }
                            }
                        }
                    )
                    Text("Tvoja ocena", color = Color(0xFF546E7A))
                    Spacer(Modifier.weight(1f))
                    Text("Prosek: ${"%.1f".format(avgRating)}", fontWeight = FontWeight.Medium)
                }
            }

            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 1.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Komentari", fontWeight = FontWeight.SemiBold)
                    var newComment by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newComment,
                            onValueChange = { newComment = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Dodaj komentar…") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val txt = newComment.trim()
                                if (txt.isNotEmpty()) {
                                    scope.launch {
                                        ParkingActions.addComment(parkingId, txt)
                                        newComment = ""
                                    }
                                }
                            },
                            enabled = newComment.trim().isNotEmpty()
                        ) { Text("Pošalji") }
                    }
                    comments.forEach { c ->
                        CommentRow(
                            item = c,
                            onLike = {
                                if (c.uid.isNotBlank()) {
                                    scope.launch { ParkingActions.likeComment(parkingId, c.id, c.uid) }
                                }
                            },
                            onDislike = {
                                scope.launch { ParkingActions.dislikeComment(parkingId, c.id) }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(88.dp))
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, value: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = value, onCheckedChange = { if (enabled) onToggle(it) }, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        Text(label)
        if (!enabled) Text("  (info)", color = Color(0xFF78909C))
    }
}

@Composable
private fun CommentRow(
    item: ParkingComment,
    onLike: () -> Unit,
    onDislike: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF9FBFF)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF90CAF9), CircleShape)
                    .border(1.dp, Color(0xFF64B5F6), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(item.uid.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.text, fontSize = 15.sp)
                Text("👍 ${item.likes}   👎 ${item.dislikes}", fontSize = 12.sp, color = Color(0xFF607D8B))
            }
            TextButton(onClick = onLike) { Text("Like") }
            TextButton(onClick = onDislike) { Text("Dislike") }
        }
    }
}

@Composable
private fun RatingBar(current: Int, onSet: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { i ->
            FilledIconToggleButton(checked = i <= current, onCheckedChange = { onSet(i) }) {
                if (i <= current) {
                    Icon(Icons.Filled.Star, contentDescription = "$i")
                } else {
                    Icon(Icons.Outlined.StarBorder, contentDescription = "$i")
                }
            }
        }
    }
}
