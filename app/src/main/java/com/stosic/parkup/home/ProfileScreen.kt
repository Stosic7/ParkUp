package com.stosic.parkup.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlin.math.max

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ProfileScreen(
    userEmail: String,
    userData: Map<String, String>?,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOpenLeaderboard: (() -> Unit)? = null
) {
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val uid = auth.currentUser?.uid

    var ime by remember { mutableStateOf("") }
    var prezime by remember { mutableStateOf("") }
    var telefon by remember { mutableStateOf("") }
    var photoUrl by remember { mutableStateOf("") }

    var bio by remember { mutableStateOf("") }
    var points by remember { mutableStateOf(0L) }
    var rank by remember { mutableStateOf(0L) }
    var parkingCount by remember { mutableStateOf(0L) }

    LaunchedEffect(userData) {
        ime = userData?.get("ime") ?: ""
        prezime = userData?.get("prezime") ?: ""
        telefon = userData?.get("telefon") ?: ""
        photoUrl = userData?.get("photoUrl") ?: ""
        bio = userData?.get("bio") ?: ""
        points = userData?.get("points")?.toLongOrNull() ?: 0L
        rank = userData?.get("rank")?.toLongOrNull() ?: 0L
        parkingCount = userData?.get("parkingCount")?.toLongOrNull() ?: 0L
    }

    var isUploading by remember { mutableStateOf(false) }
    var localPreview by remember { mutableStateOf<Uri?>(null) }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && uid != null) {
            localPreview = uri
            val storageRef = FirebaseStorage.getInstance()
                .reference.child("profile_pictures/$uid.jpg")

            isUploading = true
            storageRef.putFile(uri)
                .addOnSuccessListener {
                    storageRef.downloadUrl.addOnSuccessListener { url ->
                        val freshUrl = url.toString() + "?t=${System.currentTimeMillis()}"
                        db.collection("users").document(uid)
                            .set(mapOf("photoUrl" to freshUrl), SetOptions.merge())
                            .addOnSuccessListener {
                                isUploading = false
                                localPreview = null
                                photoUrl = freshUrl
                            }
                            .addOnFailureListener { isUploading = false }
                    }.addOnFailureListener { isUploading = false }
                }
                .addOnFailureListener { isUploading = false }
        }
    }

    // Logout confirm
    var showLogoutConfirm by remember { mutableStateOf(false) }
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            confirmButton = {
                TextButton(onClick = { showLogoutConfirm = false; onLogout() }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") } },
            title = { Text("Odjava") },
            text = { Text("Da li ste sigurni da želite da se odjavite?") }
        )
    }

    // Change password dialog state
    var showChangePass by remember { mutableStateOf(false) }
    if (showChangePass) {
        ChangePasswordDialog(
            email = auth.currentUser?.email ?: "",
            onDismiss = { showChangePass = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEAF2FF))
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Nazad", tint = Color(0xFF42A5F5))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF90CAF9))
                    .clickable {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    isUploading -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    localPreview != null -> Image(
                        painter = rememberAsyncImagePainter(localPreview),
                        contentDescription = "Profilna slika",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    photoUrl.isNotBlank() -> Image(
                        painter = rememberAsyncImagePainter(photoUrl),
                        contentDescription = "Profilna slika",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    else -> Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Profilna slika",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = "$ime $prezime",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(imageVector = Icons.Default.EmojiEvents, contentDescription = "Rang", tint = Color(0xFFFFC107))
                    Spacer(Modifier.width(6.dp))
                    Text(text = "Rank: ${if (rank > 0) "#$rank" else "-"} • Points: $points", fontSize = 14.sp)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(imageVector = Icons.Default.Email, contentDescription = "Email", tint = Color(0xFF42A5F5))
            Spacer(Modifier.width(8.dp)); Text(userEmail, fontSize = 16.sp)
        }
        if (telefon.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Icon(imageVector = Icons.Default.Phone, contentDescription = "Telefon", tint = Color(0xFF42A5F5))
                Spacer(Modifier.width(8.dp)); Text(telefon, fontSize = 16.sp)
            }
        }

        // BIO (read/edit)
        var isEditingBio by remember { mutableStateOf(false) }
        var draftBio by remember { mutableStateOf(bio) }
        var saving by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC))
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Bio", fontWeight = FontWeight.SemiBold, color = Color(0xFF2B2B2B))
                    AnimatedContent(
                        targetState = isEditingBio,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                        label = "bio-actions"
                    ) { editing ->
                        if (editing) {
                            Row {
                                IconButton(
                                    onClick = {
                                        if (uid == null) return@IconButton
                                        saving = true
                                        errorMsg = null
                                        val trimmed = draftBio.trim()
                                        FirebaseFirestore.getInstance()
                                            .collection("users").document(uid)
                                            .set(mapOf("bio" to trimmed), SetOptions.merge())
                                            .addOnSuccessListener {
                                                bio = trimmed
                                                saving = false
                                                isEditingBio = false
                                            }
                                            .addOnFailureListener {
                                                saving = false
                                                errorMsg = it.message ?: "Greška pri čuvanju"
                                            }
                                    },
                                    enabled = !saving
                                ) { Icon(Icons.Outlined.Save, contentDescription = "Save changes", tint = Color(0xFF2E7D32)) }
                                IconButton(
                                    onClick = { draftBio = bio; isEditingBio = false; errorMsg = null },
                                    enabled = !saving
                                ) { Icon(Icons.Outlined.Close, contentDescription = "Cancel", tint = Color(0xFFB71C1C)) }
                            }
                        } else {
                            IconButton(onClick = { draftBio = bio; isEditingBio = true; errorMsg = null }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Edit bio", tint = Color(0xFF1976D2))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                AnimatedContent(
                    targetState = isEditingBio,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "bio-content"
                ) { editing ->
                    if (editing) {
                        Column {
                            OutlinedTextField(
                                value = draftBio,
                                onValueChange = { draftBio = it },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                                minLines = 3,
                                maxLines = 8,
                                placeholder = { Text("Napiši nešto o sebi…") }
                            )
                            if (saving) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            if (errorMsg != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(errorMsg ?: "", color = Color(0xFFB71C1C), fontSize = 12.sp)
                            }
                        }
                    } else {
                        val shown = if (bio.isBlank()) "Nema biografije." else bio
                        Text(text = shown, fontSize = 14.sp, color = Color(0xFF333333))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        StatsCard(points = points, rank = rank, parkingCount = parkingCount)
        Spacer(Modifier.height(16.dp))
        ProgressCard(points = points)
        Spacer(Modifier.height(16.dp))
        BadgesRow(points = points)
        Spacer(Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onOpenLeaderboard?.invoke() },
                modifier = Modifier.weight(1f),
                enabled = onOpenLeaderboard != null
            ) { Text("Pogledaj rang listu") }

            OutlinedButton(
                onClick = { showChangePass = true },
                modifier = Modifier.weight(1f)
            ) { Text("Promeni lozinku") }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { showLogoutConfirm = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF42A5F5), contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Logout", fontSize = 18.sp) }
    }
}

// ====== Change password dialog ======
@Composable
private fun ChangePasswordDialog(
    email: String,
    onDismiss: () -> Unit
) {
    val auth = FirebaseAuth.getInstance()

    var oldPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var newPass2 by remember { mutableStateOf("") }

    var showOld by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showNew2 by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun validate(): String? {
        if (email.isBlank()) return "Nalog nema email adresu."
        if (oldPass.isBlank()) return "Unesi staru lozinku."
        if (newPass.length < 6) return "Nova lozinka mora imati bar 6 karaktera."
        if (newPass != newPass2) return "Nove lozinke se ne poklapaju."
        if (newPass == oldPass) return "Nova lozinka ne sme biti ista kao stara."
        return null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            TextButton(
                onClick = {
                    val err = validate()
                    if (err != null) { msg = err; return@TextButton }
                    busy = true; msg = null

                    val user = auth.currentUser
                    val cred = EmailAuthProvider.getCredential(email, oldPass)

                    // 1) reauth
                    user?.reauthenticate(cred)?.addOnCompleteListener { reauthTask ->
                        if (!reauthTask.isSuccessful) {
                            busy = false
                            msg = reauthTask.exception?.message ?: "Neuspešna reautentikacija."
                            return@addOnCompleteListener
                        }
                        // 2) update password
                        user.updatePassword(newPass).addOnCompleteListener { upd ->
                            busy = false
                            msg = if (upd.isSuccessful) {
                                "Lozinka je uspešno promenjena."
                            } else {
                                upd.exception?.message ?: "Greška pri promeni lozinke."
                            }
                            if (upd.isSuccessful) {
                                // zatvori nakon kratkog potvrđivanja
                                onDismiss()
                            }
                        }
                    }
                },
                enabled = !busy
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Sačuvaj")
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }) { Text("Otkaži") }
        },
        title = { Text("Promena lozinke") },
        text = {
            Column {
                Text("Za promenu lozinke potrebno je da se potvrdi stara lozinka (reauthenticate).", fontSize = 12.sp, color = Color(0xFF5E6A7D))
                Spacer(Modifier.height(12.dp))

                PassField(
                    label = "Stara lozinka",
                    value = oldPass,
                    onChange = { oldPass = it },
                    visible = showOld,
                    onToggle = { showOld = !showOld }
                )
                Spacer(Modifier.height(8.dp))
                PassField(
                    label = "Nova lozinka",
                    value = newPass,
                    onChange = { newPass = it },
                    visible = showNew,
                    onToggle = { showNew = !showNew }
                )
                Spacer(Modifier.height(8.dp))
                PassField(
                    label = "Ponovi novu lozinku",
                    value = newPass2,
                    onChange = { newPass2 = it },
                    visible = showNew2,
                    onToggle = { showNew2 = !showNew2 }
                )

                if (msg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(msg ?: "", color = Color(0xFFB71C1C), fontSize = 12.sp)
                }
            }
        }
    )
}

@Composable
private fun PassField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
            }
        }
    )
}

// ================== UI pomoćne kartice ==================

@Composable
private fun StatsCard(points: Long, rank: Long, parkingCount: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Statistika", fontWeight = FontWeight.SemiBold, color = Color(0xFF2B2B2B))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatPill(label = "Poeni", value = points.toString())
                StatPill(label = "Rank", value = if (rank > 0) "#$rank" else "-")
                StatPill(label = "Objave", value = parkingCount.toString())
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color(0xFF5E6A7D))
        }
    }
}

@Composable
private fun ProgressCard(points: Long) {
    val thresholds = listOf(0, 50, 150, 300, 600, 1000)
    val currentIndex = thresholds.indexOfLast { points >= it }
    val start = thresholds.getOrElse(currentIndex) { 0 }.toLong()
    val end = thresholds.getOrElse(currentIndex + 1) { (start + 1).toInt() }.toLong()
    val progress = if (end == start) 1f else ((points - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    val toNext = max(0L, end - points)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Napredak do sledećeg ranga", fontWeight = FontWeight.SemiBold, color = Color(0xFF2B2B2B))
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(8.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (toNext > 0) "Još $toNext poena do sledećeg ranga" else "Maksimalni rang u ovom modelu",
                fontSize = 12.sp,
                color = Color(0xFF5E6A7D)
            )
        }
    }
}

@Composable
private fun BadgesRow(points: Long) {
    val badges = listOf(
        "Prva objava" to (points >= 10),
        "5 objava" to (points >= 50),
        "Top 10" to (points >= 200),
        "Veteran" to (points >= 600)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Bedževi", fontWeight = FontWeight.SemiBold, color = Color(0xFF2B2B2B))
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                badges.forEach { (name, unlocked) ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (unlocked) Color.White else Color(0xFFE8EDF5),
                        tonalElevation = if (unlocked) 2.dp else 0.dp
                    ) {
                        Text(
                            text = name,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (unlocked) Color(0xFF1B5E20) else Color(0xFF7A8AA0),
                            fontSize = 12.sp,
                            fontWeight = if (unlocked) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
