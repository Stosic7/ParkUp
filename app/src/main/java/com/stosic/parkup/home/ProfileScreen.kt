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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userEmail: String,
    userData: Map<String, String>?,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOpenLeaderboard: (() -> Unit)? = null
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }
    val uid = auth.currentUser?.uid

    var ime by remember { mutableStateOf("") }
    var prezime by remember { mutableStateOf("") }
    var telefon by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(userEmail) }
    var photoUrl by remember { mutableStateOf("") }

    var bio by remember { mutableStateOf("") }
    var points by remember { mutableStateOf(0L) }
    var rank by remember { mutableStateOf(0L) }
    var parkingCount by remember { mutableStateOf(0L) }

    var isEditingBio by remember { mutableStateOf(false) }
    var draftBio by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var userDocReg by remember { mutableStateOf<ListenerRegistration?>(null) }
    var usersReg by remember { mutableStateOf<ListenerRegistration?>(null) }

    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showChangePass by remember { mutableStateOf(false) }

    var didPrefill by remember { mutableStateOf(false) }
    LaunchedEffect(userData) {
        if (!didPrefill && userData != null) {
            ime = userData["ime"] ?: ""
            prezime = userData["prezime"] ?: ""
            telefon = userData["telefon"] ?: ""
            email = userData["email"] ?: email
            photoUrl = userData["photoUrl"] ?: ""
            bio = userData["bio"] ?: ""
            points = userData["points"]?.toLongOrNull() ?: 0L
            parkingCount = userData["parkingCount"]?.toLongOrNull() ?: 0L
            didPrefill = true
        }
    }

    DisposableEffect(uid) {
        if (uid != null) {
            userDocReg?.remove()
            userDocReg = db.collection("users").document(uid)
                .addSnapshotListener { doc, _ ->
                    if (doc != null && doc.exists()) {
                        ime = doc.getString("ime") ?: ime
                        prezime = doc.getString("prezime") ?: prezime
                        telefon = doc.getString("telefon") ?: telefon
                        email = doc.getString("email") ?: email
                        photoUrl = doc.getString("photoUrl") ?: photoUrl
                        bio = doc.getString("bio") ?: bio
                        points = doc.getLong("points") ?: points
                        parkingCount = doc.getLong("parkingCount") ?: parkingCount
                    }
                }
        }
        onDispose { userDocReg?.remove(); userDocReg = null }
    }

    DisposableEffect(uid) {
        usersReg?.remove()
        usersReg = db.collection("users")
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.map { d ->
                    val pts = d.getLong("points") ?: 0L
                    val id = d.id
                    id to pts
                }.orEmpty()
                val ordered = list.sortedWith(
                    compareByDescending<Pair<String, Long>> { it.second }
                        .thenBy { it.first }
                )
                val idx = ordered.indexOfFirst { it.first == uid }
                if (idx >= 0) rank = (idx + 1).toLong()
            }
        onDispose { usersReg?.remove(); usersReg = null }
    }

    var isUploading by remember { mutableStateOf(false) }
    var localPreview by remember { mutableStateOf<Uri?>(null) }
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && uid != null) {
            localPreview = uri
            val storageRef = FirebaseStorage.getInstance().reference.child("profile_pictures/$uid.jpg")
            isUploading = true
            storageRef.putFile(uri)
                .addOnSuccessListener {
                    storageRef.downloadUrl.addOnSuccessListener { url ->
                        FirebaseFirestore.getInstance().collection("users").document(uid)
                            .update(mapOf("photoUrl" to url.toString()))
                            .addOnSuccessListener {
                                photoUrl = url.toString()
                                isUploading = false
                            }
                            .addOnFailureListener { isUploading = false }
                    }.addOnFailureListener { isUploading = false }
                }
                .addOnFailureListener { isUploading = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Nazad", tint = Color.White)
                    }
                },
                title = { Text("Profil", color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontStyle = FontStyle.Italic) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF42A5F5))
            )
        },
        containerColor = Color(0xFFF0F4F8)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF90CAF9))
                        .clickable { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center
                ) {
                    val preview = localPreview
                    when {
                        preview != null -> {
                            AsyncImage(
                                model = preview,
                                contentDescription = "Profilna slika",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        photoUrl.isNotBlank() -> {
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = "Profilna slika",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profilna slika",
                                tint = Color.White,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    val displayName = listOf(ime, prezime).filter { it.isNotBlank() }.joinToString(" ").ifBlank { email }
                    Text(
                        text = displayName,
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
                            label = "bio-edit"
                        ) { editing ->
                            Row {
                                if (editing) {
                                    IconButton(
                                        onClick = {
                                            saving = true
                                            val data = hashMapOf("bio" to draftBio)
                                            FirebaseFirestore.getInstance().collection("users").document(uid!!)
                                                .update(data as Map<String, Any>)
                                                .addOnSuccessListener {
                                                    bio = draftBio
                                                    errorMsg = null
                                                    saving = false
                                                    isEditingBio = false
                                                }
                                                .addOnFailureListener {
                                                    saving = false
                                                    errorMsg = it.message ?: "Greška pri čuvanju"
                                                }
                                        },
                                        enabled = !saving
                                    ) { Icon(Icons.Outlined.Save, contentDescription = "Sačuvaj", tint = Color(0xFF2E7D32)) }
                                    IconButton(
                                        onClick = { draftBio = bio; isEditingBio = false; errorMsg = null },
                                        enabled = !saving
                                    ) { Icon(Icons.Outlined.Close, contentDescription = "Otkaži", tint = Color(0xFFB71C1C)) }
                                } else {
                                    IconButton(onClick = { draftBio = bio; isEditingBio = true; errorMsg = null }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = "Izmeni bio", tint = Color(0xFF1976D2))
                                    }
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp),
                                    placeholder = { Text("Napiši nešto o sebi...") }
                                )
                                if (errorMsg != null) {
                                    Text(errorMsg!!, color = Color(0xFFB71C1C), modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        } else {
                            Text(
                                text = if (bio.isBlank()) "Nema opisa." else bio,
                                color = Color(0xFF37474F)
                            )
                        }
                    }
                }
            }

            StatsCard(points = points, rank = rank, parkingCount = parkingCount)
            ProgressCard(points = points)
            BadgesRow(points = points)

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

            Button(
                onClick = { showLogoutConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF42A5F5), contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) { Text("Logout", fontSize = 18.sp) }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            confirmButton = { TextButton(onClick = { showLogoutConfirm = false; onLogout() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") } },
            title = { Text("Odjava") },
            text = { Text("Da li ste sigurni da želite da se odjavite?") }
        )
    }

    if (showChangePass) {
        ChangePasswordDialog(
            email = email,
            onDismiss = { showChangePass = false }
        )
    }
}

@Composable
private fun StatsCard(points: Long, rank: Long, parkingCount: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Poeni", fontWeight = FontWeight.SemiBold)
                    Text("$points", fontSize = 20.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Rang", fontWeight = FontWeight.SemiBold)
                    Text(if (rank > 0) "#$rank" else "-", fontSize = 20.sp)
                }
            }
            Divider()
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Parking objave", fontWeight = FontWeight.SemiBold)
                    Text("$parkingCount", fontSize = 20.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Status", fontWeight = FontWeight.SemiBold)
                    Text(if (points > 0) "Aktivan" else "Nov", fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(points: Long) {
    val maxForTier = 100L
    val progress = (points.coerceAtMost(maxForTier)).toFloat() / maxForTier.toFloat()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Napredak do sledećeg ranga", fontWeight = FontWeight.SemiBold, color = Color(0xFF2B2B2B))
            LinearProgressIndicator(progress = progress, modifier = Modifier
                .fillMaxWidth()
                .height(8.dp))
            val toNext = (maxForTier - points).coerceAtLeast(0)
            Text(
                text = if (toNext > 0) "Još $toNext poena do sledećeg ranga" else "Maksimalni rang u ovom modelu",
                fontSize = 12.sp,
                color = Color(0xFF607D8B)
            )
        }
    }
}

@Composable
private fun BadgesRow(points: Long) {
    val badges = listOf(
        "Starter" to (points >= 0),
        "Contributor" to (points >= 10),
        "Helper" to (points >= 25),
        "Pro" to (points >= 50),
        "Elite" to (points >= 100)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        badges.forEach { (name, unlocked) ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (unlocked) Color(0xFFE8F5E9) else Color(0xFFF0F4F8))
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .heightIn(min = 40.dp),
                    contentAlignment = Alignment.Center
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

@Composable
private fun ChangePasswordDialog(
    email: String,
    onDismiss: () -> Unit
) {
    var currentPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var showCur by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showConf by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val auth = FirebaseAuth.getInstance()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Promena lozinke") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = currentPass,
                    onValueChange = { currentPass = it },
                    label = { Text("Trenutna lozinka") },
                    visualTransformation = if (showCur) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showCur = !showCur }) {
                            Icon(if (showCur) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPass,
                    onValueChange = { newPass = it },
                    label = { Text("Nova lozinka") },
                    visualTransformation = if (showNew) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showNew = !showNew }) {
                            Icon(if (showNew) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPass,
                    onValueChange = { confirmPass = it },
                    label = { Text("Ponovi novu lozinku") },
                    visualTransformation = if (showConf) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showConf = !showConf }) {
                            Icon(if (showConf) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (err != null) Text(err!!, color = Color(0xFFB71C1C))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newPass.length < 6) {
                        err = "Nova lozinka mora imati bar 6 karaktera"
                        return@TextButton
                    }
                    if (newPass != confirmPass) {
                        err = "Nova lozinka i potvrda se ne poklapaju"
                        return@TextButton
                    }
                    val user = auth.currentUser
                    val mail = user?.email ?: email
                    val cred = EmailAuthProvider.getCredential(mail, currentPass)
                    user?.reauthenticate(cred)
                        ?.addOnSuccessListener {
                            user.updatePassword(newPass)
                                .addOnSuccessListener { onDismiss() }
                                .addOnFailureListener { e -> err = e.message ?: "Greška pri promeni lozinke" }
                        }
                        ?.addOnFailureListener { e -> err = e.message ?: "Pogrešna trenutna lozinka" }
                }
            ) { Text("Sačuvaj") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Otkaži") } }
    )
}
