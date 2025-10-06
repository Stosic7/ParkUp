package com.stosic.parkup.parking.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.stosic.parkup.parking.data.ParkingActions
import com.stosic.parkup.parking.data.ParkingComment
import com.stosic.parkup.parking.data.ParkingRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

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
    val context = LocalContext.current
    val contextState = rememberUpdatedState(context)
    var avgRating by remember { mutableStateOf(0.0) }
    var myRating by remember { mutableStateOf(0.0) }
    var comments by remember { mutableStateOf<List<ParkingComment>>(emptyList()) }
    var capacity by remember { mutableStateOf<Long?>(null) }
    var available by remember { mutableStateOf<Long?>(null) }
    var pricePerHour by remember { mutableStateOf<Long?>(null) }
    var hasEv by remember { mutableStateOf(false) }
    var hasRamp by remember { mutableStateOf(false) }
    var createdBy by remember { mutableStateOf<String?>(null) }
    var creatorName by remember { mutableStateOf<String?>(null) }
    val isCreator = remember(uid, createdBy) { uid != null && uid == createdBy }
    var placeType by remember { mutableStateOf<String?>(null) } // "street" | "garage"
    var zone by remember { mutableStateOf<String?>(null) }       // "green" | "red" | "extra"
    var isDisabledSpot by remember { mutableStateOf<Boolean?>(null) }
    var photoBase64 by remember { mutableStateOf<String?>(null) }
    var localPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var uploadingPhoto by remember { mutableStateOf(false) }
    var hasActiveReservation by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // loading basic parking information (one-time)
    LaunchedEffect(parkingId) {
        val doc = db.collection("parkings").document(parkingId).get().await()
        capacity = doc.getLong("capacity")
        available = doc.getLong("availableSlots")
        pricePerHour = doc.getLong("pricePerHour")
        hasEv = doc.getBoolean("hasEv") ?: false
        hasRamp = doc.getBoolean("hasRamp") ?: false
        createdBy = doc.getString("createdBy")
        photoBase64 = doc.getString("photoBase64")
        placeType = doc.getString("placeType")
        zone = doc.getString("zone")
        isDisabledSpot = doc.getBoolean("disabledSpot")
    }

    // catching author name and surname
    LaunchedEffect(createdBy) {
        val c = createdBy ?: return@LaunchedEffect
        val doc = db.collection("users").document(c).get().await()
        val ime = doc.getString("ime") ?: ""
        val prezime = doc.getString("prezime") ?: ""
        creatorName = listOf(ime, prezime).filter { it.isNotBlank() }.joinToString(" ").ifBlank { c }
    }

    // live catching ratings
    DisposableEffect(parkingId, uid) {
        val ratingsReg = db.collection("parkings").document(parkingId)
            .collection("ratings")
            .addSnapshotListener { qs, _ ->
                val stars = qs?.documents?.mapNotNull {
                    it.getDouble("stars") ?: it.getLong("stars")?.toDouble()
                } ?: emptyList()
                avgRating = if (stars.isNotEmpty()) stars.average() else 0.0
                if (uid != null) {
                    val me = qs?.documents
                        ?.firstOrNull { it.id == uid }
                        ?.let { it.getDouble("stars") ?: it.getLong("stars")?.toDouble() }
                    if (me != null) myRating = me
                }
            }
        onDispose { ratingsReg.remove() }
    }

    val authorPhotos = remember { mutableStateMapOf<String, String?>() }
    val myVotes = remember { mutableStateMapOf<String, String?>() }

    // real-time comment list
    DisposableEffect(parkingId) {
        val reg = db.collection("parkings").document(parkingId)
            .collection("comments")
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(ParkingComment::class.java) }.orEmpty()
                comments = list.sortedByDescending { it.createdAt }
                val missing = list.map { it.uid }.distinct().filter { it !in authorPhotos.keys }
                missing.forEach { u ->
                    db.collection("users").document(u).get()
                        .addOnSuccessListener { d -> authorPhotos[u] = d.getString("photoBase64") }
                }
                val me = uid
                if (me != null) {
                    list.forEach { c ->
                        db.collection("parkings").document(parkingId)
                            .collection("comments").document(c.id)
                            .collection("votes").document(me)
                            .get()
                            .addOnSuccessListener { v -> myVotes[c.id] = v.getString("vote") }
                    }
                }
            }
        onDispose { reg.remove() }
    }
    // live status of my reservation on specific parking
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

    fun uriToBase64(uri: Uri, maxSize: Int = 1280, quality: Int = 85): String {
        val ctx = contextState.value
        val bmp: Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri))
        val scale = minOf(maxSize / bmp.width.toFloat(), maxSize / bmp.height.toFloat(), 1f)
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val resized = if (w != bmp.width || h != bmp.height) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && isCreator) {
            localPhotoUri = uri
            uploadingPhoto = true
            scope.launch {
                val b64 = runCatching { uriToBase64(uri) }.getOrNull()
                if (b64 != null) {
                    ParkingRepository.setParkingPhoto(parkingId, b64)
                    photoBase64 = b64
                }
                uploadingPhoto = false
            }
        }
    }

    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null && isCreator) {
            uploadingPhoto = true
            scope.launch {
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                ParkingRepository.setParkingPhoto(parkingId, b64)
                photoBase64 = b64
                localPhotoUri = null
                uploadingPhoto = false
            }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocalParking, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(parkingTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF42A5F5))
            )
        },
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
                        Text("Reserve")
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
                        Text("End reservation")
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
            if (creatorName != null) {
                Text(
                    text = "Creator: ${creatorName}",
                    color = Color(0xFF546E7A),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                color = Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 280.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            localPhotoUri != null -> {
                                AsyncImage(
                                    model = localPhotoUri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            !photoBase64.isNullOrBlank() -> {
                                val bytes = remember(photoBase64) { Base64.decode(photoBase64, Base64.DEFAULT) }
                                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text("No photo available for this parking spot.", color = Color(0xFF78909C))
                                }
                            }
                            else -> {
                                Text("No photo available for this parking spot.", color = Color(0xFF78909C))
                            }
                        }
                    }
                    if (isCreator) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                enabled = !uploadingPhoto
                            ) {
                                Text(if (uploadingPhoto) "Saving..." else if (photoBase64.isNullOrBlank()) "Add photo" else "Change photo")
                            }
                            OutlinedButton(
                                onClick = { camera.launch(null) },
                                enabled = !uploadingPhoto
                            ) { Text(if (uploadingPhoto) "Saving..." else "Take a photo") }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    parkingTitle,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Average: ", fontSize = 14.sp, color = Color(0xFF546E7A))
                    Text("${"%.1f".format(avgRating)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            val cap = capacity; val avail = available
            if (cap != null && avail != null) {
                Row {
                    Text("Capacity: ", fontSize = 14.sp, color = Color(0xFF546E7A))
                    Text("$avail / $cap", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 1.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Price per hour: ", fontSize = 14.sp, color = Color(0xFF546E7A))
                        Spacer(Modifier.width(8.dp))
                        Text("${pricePerHour ?: 0} RSD/h", fontWeight = FontWeight.Medium)
                    }

                    if (!isCreator) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            LabeledSwitch("EV charger", hasEv)
                            LabeledSwitch("Ramp", hasRamp)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            EditableSwitch(
                                label = "EV charger",
                                checked = hasEv
                            ) { newVal ->
                                scope.launch {
                                    db.collection("parkings").document(parkingId)
                                        .set(mapOf("hasEv" to newVal), SetOptions.merge())
                                        .await()
                                    hasEv = newVal
                                }
                            }
                            EditableSwitch(
                                label = "Ramp",
                                checked = hasRamp
                            ) { newVal ->
                                scope.launch {
                                    db.collection("parkings").document(parkingId)
                                        .set(mapOf("hasRamp" to newVal), SetOptions.merge())
                                        .await()
                                    hasRamp = newVal
                                }
                            }
                        }
                    }

                    val zoneColor = when (zone) {
                        "green" -> Color(0xFF2E7D32)
                        "red" -> Color(0xFFC62828)
                        "extra" -> Color(0xFFFFC107)
                        else -> Color(0xFF90A4AE)
                    }
                    val typeLabel = when (placeType) { "garage" -> "Garage"; else -> "Street" }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { },
                            enabled = false,
                            label = { Text("Type: $typeLabel") }
                        )
                        AssistChip(
                            onClick = { },
                            enabled = false,
                            label = { Text("Zone") },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(zoneColor, CircleShape)
                                )
                            }
                        )
                        if (isDisabledSpot == true) {
                            AssistChip(
                                onClick = { },
                                enabled = false,
                                label = { Text("Accessible parking spot") }
                            )
                        }
                    }
                }
            }

            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 1.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isCreator) {
                        Text("Your rating", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            repeat(5) { idx ->
                                val i = idx + 1
                                val icon = when {
                                    myRating >= i       -> Icons.Filled.Star
                                    myRating >= i - 0.5 -> Icons.Filled.StarHalf
                                    else                -> Icons.Outlined.Star
                                }
                                val tint = if (myRating >= i - 0.5) Color(0xFFFFC107) else Color(0xFFB0BEC5)

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .pointerInput(i) {
                                            detectTapGestures { offset ->
                                                val me = uid ?: return@detectTapGestures
                                                val half = offset.x < size.width / 2f
                                                val newRating = if (half) i - 0.5 else i.toDouble()
                                                myRating = newRating
                                                db.collection("parkings").document(parkingId)
                                                    .collection("ratings").document(me)
                                                    .set(mapOf("stars" to newRating, "updatedAt" to System.currentTimeMillis()))
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = icon, contentDescription = "$i", tint = tint)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (avgRating > 0) String.format("Average: %.1f/5", avgRating) else "No ratings",
                                color = Color(0xFF546E7A)
                            )
                        }
                    } else {
                        Text(
                            "As the creator, you can’t rate your own parking.",
                            color = Color(0xFF546E7A)
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            repeat(5) { idx ->
                                val i = idx + 1
                                val icon = when {
                                    avgRating >= i       -> Icons.Filled.Star
                                    avgRating >= i - 0.5 -> Icons.Filled.StarHalf
                                    else                 -> Icons.Outlined.Star
                                }
                                val tint = Color(0xFFCFD8DC)
                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = icon, contentDescription = "$i", tint = tint)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (avgRating > 0) String.format("Average: %.1f/5", avgRating) else "No ratings",
                                color = Color(0xFF546E7A)
                            )
                        }
                    }
                }
            }

            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Comments", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    var newComment by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newComment,
                            onValueChange = { newComment = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Add a comment...") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    ParkingActions.addComment(parkingId, newComment.trim())
                                    newComment = ""
                                }
                            },
                            enabled = newComment.trim().isNotEmpty()
                        ) { Text("Send") }
                    }

                    comments.forEach { c ->
                        val avatarB64 = authorPhotos[c.uid]
                        val myVote = myVotes[c.id]
                        CommentRow(
                            item = c,
                            authorPhotoBase64 = avatarB64,
                            myVote = myVote,
                            isMine = (uid == c.uid),
                            onVote = { target ->
                                if (uid == c.uid) return@CommentRow
                                scope.launch {
                                    ParkingActions.voteComment(parkingId, c.id, c.uid, target)
                                }
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
private fun LabeledSwitch(label: String, value: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = value,
            onCheckedChange = { _: Boolean -> },
            enabled = false
        )
        Spacer(Modifier.width(6.dp))
        Text(label)
        Text("  (info)", color = Color(0xFF78909C))
    }
}

@Composable
private fun EditableSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.Medium)
        Text(" (changes are saved immediately)", color = Color(0xFF78909C), fontSize = 12.sp)
    }
}

@Composable
private fun CommentRow(
    item: com.stosic.parkup.parking.data.ParkingComment,
    authorPhotoBase64: String?,
    myVote: String?, // "like", "dislike", or null
    isMine: Boolean,
    onVote: (vote: String) -> Unit // sending "like" | "dislike" | "none"
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF9FBFF)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF90CAF9)),
                contentAlignment = Alignment.Center
            ) {
                if (!authorPhotoBase64.isNullOrBlank()) {
                    val bytes = remember(authorPhotoBase64) { Base64.decode(authorPhotoBase64, Base64.DEFAULT) }
                    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    if (bmp != null) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                } else {
                    Text(item.uid.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.text, fontSize = 15.sp)
                Text("👍 ${item.likes}   👎 ${item.dislikes}", fontSize = 12.sp, color = Color(0xFF607D8B))
            }

            val likeEnabled = !isMine && myVote != "dislike"
            val dislikeEnabled = !isMine && myVote != "like"

            TextButton(
                onClick = {
                    val target = if (myVote == "like") "none" else "like"
                    onVote(target)
                },
                enabled = likeEnabled
            ) { Text("Like") }

            TextButton(
                onClick = {
                    val target = if (myVote == "dislike") "none" else "dislike"
                    onVote(target)
                },
                enabled = dislikeEnabled
            ) { Text("Dislike") }
        }
    }
}
