package com.stosic.parkup.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.Edit
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import com.stosic.parkup.leaderboard.model.LB_COMPARATOR
import com.stosic.parkup.leaderboard.model.LbEntry
import com.stosic.parkup.leaderboard.model.docToLbEntry
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.tasks.await
import androidx.compose.material.icons.filled.Close
import java.io.ByteArrayOutputStream

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
    val ctx = LocalContext.current
    var ime by remember { mutableStateOf("") }
    var prezime by remember { mutableStateOf("") }
    var telefon by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(userEmail) }
    var photoBase64 by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var isEditingBio by remember { mutableStateOf(false) }
    var bioDraft by remember { mutableStateOf(bio) }
    var savingBio by remember { mutableStateOf(false) }

    LaunchedEffect(bio, isEditingBio) {
        if (!isEditingBio) bioDraft = bio
    }

    var points by remember { mutableStateOf(0L) }
    var rank by remember { mutableStateOf(0L) }
    var parkingCount by remember { mutableStateOf(0L) }
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
            bio = userData["bio"] ?: ""
            points = userData["points"]?.toLongOrNull() ?: 0L
            parkingCount = userData["parkingCount"]?.toLongOrNull() ?: 0L
            didPrefill = true
        }
    }

    // gathering information from Firebase and filling in the details.
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
                        photoBase64 = doc.getString("photoBase64") ?: photoBase64
                        bio = doc.getString("bio") ?: bio
                        points = doc.getLong("points") ?: points
                        parkingCount = doc.getLong("parkingCount") ?: parkingCount
                    }
                }
        }
        onDispose { userDocReg?.remove(); userDocReg = null }
    }

    // calculation the rank
    DisposableEffect(uid) {
        usersReg?.remove()
        usersReg = db.collection("users")
            .addSnapshotListener { snap, _ ->
                val everyone: List<LbEntry> =
                    snap?.documents?.map { d -> docToLbEntry(d) }.orEmpty()

                val ordered = everyone.sortedWith(LB_COMPARATOR)

                val idx = if (uid == null) -1 else ordered.indexOfFirst { it.uid == uid }
                rank = if (idx >= 0) (idx + 1).toLong() else 0L
            }
        onDispose { usersReg?.remove(); usersReg = null }
    }

    // Pending Base64 from registrations (SharedPreferences)
    LaunchedEffect(uid) {
        if (uid != null) {
            val prefs = ctx.getSharedPreferences("parkup_prefs", Context.MODE_PRIVATE)
            val pending = prefs.getString("pending_profile_b64", null)
            if (!pending.isNullOrBlank()) {
                runCatching {
                    db.collection("users").document(uid).update("photoBase64", pending).await()
                    photoBase64 = pending
                }
                prefs.edit().remove("pending_profile_b64").apply()
            }
        }
    }

    var localPreview by remember { mutableStateOf<Uri?>(null) }
    var isUploading by remember { mutableStateOf(false) }

    fun uriToBase64(context: Context, uri: Uri, maxSizePx: Int = 256, quality: Int = 80): String {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src)
        } else {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                BitmapFactory.decodeStream(input)
            }
        }
        val scale = minOf(maxSizePx / bmp.width.toFloat(), maxSizePx / bmp.height.toFloat(), 1f)
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val resized = if (w != bmp.width || h != bmp.height) {
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } else bmp
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun base64ToBitmap(b64: String): Bitmap? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) { null }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && uid != null) {
            localPreview = uri
            isUploading = true
            runCatching {
                val b64 = uriToBase64(ctx, uri)
                db.collection("users").document(uid)
                    .update(mapOf("photoBase64" to b64))
                    .addOnSuccessListener {
                        photoBase64 = b64
                        isUploading = false
                    }
                    .addOnFailureListener { isUploading = false }
            }.onFailure { isUploading = false }
        }
    }

    val takePhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null && uid != null) {
            isUploading = true
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            db.collection("users").document(uid)
                .update(mapOf("photoBase64" to b64))
                .addOnSuccessListener {
                    photoBase64 = b64
                    localPreview = null
                    isUploading = false
                }
                .addOnFailureListener { isUploading = false }
        }
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
                    Text("Profile", color = Color.White, fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
                },
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
                        .clickable {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        localPreview != null -> {
                            AsyncImage(
                                model = localPreview,
                                contentDescription = "Profilna",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        photoBase64.isNotBlank() -> {
                            val bmp = remember(photoBase64) { base64ToBitmap(photoBase64) }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Profilna",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
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
                        Icon(imageVector = Icons.Filled.EmojiEvents, contentDescription = "Rang", tint = Color(0xFFFFC107))
                        Spacer(Modifier.width(6.dp))
                        Text(text = "Rank: ${if (rank > 0) "#$rank" else "-"} • Points: $points", fontSize = 14.sp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !isUploading
                ) { Text(if (isUploading) "Loading..." else "Change (Gallery)") }

                OutlinedButton(
                    onClick = { takePhoto.launch(null) },
                    enabled = !isUploading
                ) { Text(if (isUploading) "Loading..." else "Take a photo (Camera)") }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (!isEditingBio) {
                        IconButton(
                            onClick = { if (uid != null) isEditingBio = true },
                            enabled = uid != null,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit bio", tint = Color(0xFF546E7A))
                        }
                    }

                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("About me", fontWeight = FontWeight.SemiBold, color = Color(0xFF2B2B2B))

                        if (isEditingBio) {
                            OutlinedTextField(
                                value = bioDraft,
                                onValueChange = { bioDraft = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp),
                                placeholder = { Text("Write something about yourself...") },
                                singleLine = false,
                                maxLines = 6
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = {
                                        if (uid == null) return@Button
                                        savingBio = true
                                        val finalText = bioDraft.trim()
                                        FirebaseFirestore.getInstance()
                                            .collection("users").document(uid)
                                            .update("bio", finalText)
                                            .addOnSuccessListener {
                                                savingBio = false
                                                isEditingBio = false
                                            }
                                            .addOnFailureListener {
                                                savingBio = false
                                            }
                                    },
                                    enabled = !savingBio && uid != null
                                ) { Text(if (savingBio) "Saving..." else "Save") }

                                OutlinedButton(
                                    onClick = {
                                        bioDraft = bio
                                        isEditingBio = false
                                    },
                                    enabled = !savingBio
                                ) { Text("Cancel") }
                            }
                        } else {
                            // prikaz bio teksta (ili placeholder)
                            val shown = bio.takeIf { it.isNotBlank() } ?: "No information about the user."
                            Text(
                                shown,
                                color = Color(0xFF37474F),
                                lineHeight = 20.sp
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
                ) { Text("View leaderboard") }

                OutlinedButton(
                    onClick = { showChangePass = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Change password") }
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
            title = { Text("Logout") },
            text = { Text("Are you sure you want to log out?") }
        )
    }

    if (showChangePass) {
        ChangePasswordDialog(email = email, onDismiss = { showChangePass = false })
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
                Column { Text("Points", fontWeight = FontWeight.SemiBold); Text("$points", fontSize = 20.sp) }
                Column(horizontalAlignment = Alignment.End) { Text("Rank", fontWeight = FontWeight.SemiBold); Text(if (rank > 0) "#$rank" else "-", fontSize = 20.sp) }
            }
            Divider()
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column { Text("Parking spots added", fontWeight = FontWeight.SemiBold); Text("$parkingCount", fontSize = 20.sp) }
                Column(horizontalAlignment = Alignment.End) { Text("Status", fontWeight = FontWeight.SemiBold); Text(if (points > 0) "Active" else "New", fontSize = 20.sp) }
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
            Text("Progress to the next rank", fontWeight = FontWeight.SemiBold, color = Color(0xFF2B2B2B))
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(8.dp))
            val toNext = (maxForTier - points).coerceAtLeast(0)
            Text(if (toNext > 0) "$toNext points to the next rank" else "Maximum rank in this model", fontSize = 12.sp, color = Color(0xFF607D8B))
        }
    }
}

@Composable
private fun BadgesRow(points: Long) {
    val badges = listOf(
        "Starter" to (points >= 0), // newly created account
        "Contributor" to (points >= 10), // at least 10 points
        "Helper" to (points >= 50), // at least 50 points
        "Pro" to (points >= 75), // at least 75 points
        "Elite" to (points >= 100) // 100 and over
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        badges.forEach { (name, unlocked) ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (unlocked) Color(0xFFE8F5E9) else Color(0xFFF0F4F8))
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).heightIn(min = 40.dp),
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
private fun ChangePasswordDialog(email: String, onDismiss: () -> Unit) {
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
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = currentPass, onValueChange = { currentPass = it },
                    label = { Text("Current password") },
                    visualTransformation = if (showCur) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showCur = !showCur }) { Icon(if (showCur) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPass, onValueChange = { newPass = it },
                    label = { Text("New password") },
                    visualTransformation = if (showNew) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showNew = !showNew }) { Icon(if (showNew) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPass, onValueChange = { confirmPass = it },
                    label = { Text("Repeat new password") },
                    visualTransformation = if (showConf) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showConf = !showConf }) { Icon(if (showConf) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (err != null) Text(err!!, color = Color(0xFFB71C1C))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newPass.length < 6) { err = "The new password must be at least 6 characters long."; return@TextButton }
                    if (newPass != confirmPass) { err = "Passwords do not match."; return@TextButton }
                    val user = auth.currentUser
                    val mail = user?.email ?: email
                    val cred = EmailAuthProvider.getCredential(mail, currentPass)
                    user?.reauthenticate(cred)
                        ?.addOnSuccessListener {
                            user.updatePassword(newPass)
                                .addOnSuccessListener { onDismiss() }
                                .addOnFailureListener { e -> err = e.message ?: "Error while changing the password" }
                        }
                        ?.addOnFailureListener { e -> err = e.message ?: "Incorrect current password" }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
