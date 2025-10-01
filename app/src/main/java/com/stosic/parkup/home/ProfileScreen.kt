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

    // Prefill iz userData (ostavljeno kako je bilo)
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

    // Realtime korisnik (uključuje photoBase64)
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

    // Rang (ostavljeno)
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
                    compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }
                )
                val idx = ordered.indexOfFirst { it.first == uid }
                if (idx >= 0) rank = (idx + 1).toLong()
            }
        onDispose { usersReg?.remove(); usersReg = null }
    }

    // Pending Base64 iz registracije (SharedPreferences)
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

    // Local preview i upload state
    var localPreview by remember { mutableStateOf<Uri?>(null) }
    var isUploading by remember { mutableStateOf(false) }

    // Helperi za kodiranje/dekodiranje
    fun uriToBase64(context: Context, uri: Uri, maxSizePx: Int = 256, quality: Int = 80): String {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src)
        } else {
            // Fallback za starije
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

    // Galerija → Base64 → Firestore
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && uid != null) {
            localPreview = uri  // odmah pokaži preview
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

    // Kamera → Bitmap → Base64 → Firestore
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
                    Text("Profil", color = Color.White, fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
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
                        // 1) lokalni preview (URI) – AsyncImage
                        localPreview != null -> {
                            AsyncImage(
                                model = localPreview,
                                contentDescription = "Profilna",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // 2) trajna slika iz Firestore (Base64) – ručni decode u Bitmap
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
                        // 3) fallback – ikonica
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
                ) { Text(if (isUploading) "Učitavam..." else "Promeni (Galerija)") }

                OutlinedButton(
                    onClick = { takePhoto.launch(null) },
                    enabled = !isUploading
                ) { Text(if (isUploading) "Učitavam..." else "Uslikaj (Kamera)") }
            }

            // --- BIO bubble ---
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth()) {
                    // Olovka gore desno (samo kada ne editujemo)
                    if (!isEditingBio) {
                        IconButton(
                            onClick = { if (uid != null) isEditingBio = true },
                            enabled = uid != null,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Uredi bio", tint = Color(0xFF546E7A))
                        }
                    }

                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("O meni", fontWeight = FontWeight.SemiBold, color = Color(0xFF2B2B2B))

                        if (isEditingBio) {
                            OutlinedTextField(
                                value = bioDraft,
                                onValueChange = { bioDraft = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp),
                                placeholder = { Text("Napiši nešto o sebi...") },
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
                                                // lokalno osveži
                                                // (snapshot listener će ionako dohvatiti, ali odmah ažuriramo UI)
                                                savingBio = false
                                                isEditingBio = false
                                            }
                                            .addOnFailureListener {
                                                savingBio = false
                                            }
                                    },
                                    enabled = !savingBio && uid != null
                                ) { Text(if (savingBio) "Čuvam..." else "Sačuvaj") }

                                OutlinedButton(
                                    onClick = {
                                        // otkaži izmene – vrati na original i zatvori edit
                                        bioDraft = bio
                                        isEditingBio = false
                                    },
                                    enabled = !savingBio
                                ) { Text("Otkaži") }
                            }
                        } else {
                            // prikaz bio teksta (ili placeholder)
                            val shown = bio.takeIf { it.isNotBlank() } ?: "Nije dodato ništa o korisniku."
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
        ChangePasswordDialog(email = email, onDismiss = { showChangePass = false })
    }
}

/* --- Ostali kompozabli (StatsCard, ProgressCard, BadgesRow, ChangePasswordDialog) ostaju ISTI kao kod tebe --- */

@Composable
private fun StatsCard(points: Long, rank: Long, parkingCount: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column { Text("Poeni", fontWeight = FontWeight.SemiBold); Text("$points", fontSize = 20.sp) }
                Column(horizontalAlignment = Alignment.End) { Text("Rang", fontWeight = FontWeight.SemiBold); Text(if (rank > 0) "#$rank" else "-", fontSize = 20.sp) }
            }
            Divider()
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column { Text("Parking objave", fontWeight = FontWeight.SemiBold); Text("$parkingCount", fontSize = 20.sp) }
                Column(horizontalAlignment = Alignment.End) { Text("Status", fontWeight = FontWeight.SemiBold); Text(if (points > 0) "Aktivan" else "Nov", fontSize = 20.sp) }
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
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(8.dp))
            val toNext = (maxForTier - points).coerceAtLeast(0)
            Text(if (toNext > 0) "Još $toNext poena do sledećeg ranga" else "Maksimalni rang u ovom modelu", fontSize = 12.sp, color = Color(0xFF607D8B))
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
        title = { Text("Promena lozinke") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = currentPass, onValueChange = { currentPass = it },
                    label = { Text("Trenutna lozinka") },
                    visualTransformation = if (showCur) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showCur = !showCur }) { Icon(if (showCur) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPass, onValueChange = { newPass = it },
                    label = { Text("Nova lozinka") },
                    visualTransformation = if (showNew) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showNew = !showNew }) { Icon(if (showNew) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPass, onValueChange = { confirmPass = it },
                    label = { Text("Ponovi novu lozinku") },
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
                    if (newPass.length < 6) { err = "Nova lozinka mora imati bar 6 karaktera"; return@TextButton }
                    if (newPass != confirmPass) { err = "Nova lozinka i potvrda se ne poklapaju"; return@TextButton }
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
