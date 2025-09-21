package com.stosic.parkup.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage

@Composable
fun ProfileScreen(
    userEmail: String,
    userData: Map<String, String>?,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val db = FirebaseFirestore.getInstance()
    val uid = FirebaseAuth.getInstance().currentUser?.uid

    var ime by remember { mutableStateOf(userData?.get("ime") ?: "") }
    var prezime by remember { mutableStateOf(userData?.get("prezime") ?: "") }
    var telefon by remember { mutableStateOf(userData?.get("telefon") ?: "") }
    var photoUrl by remember { mutableStateOf<String?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var localPreview by remember { mutableStateOf<Uri?>(null) } // 👈 preview lokalno

    // Moderni Photo Picker
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && uid != null) {
            localPreview = uri // odmah prikaži preview

            val storageRef = FirebaseStorage.getInstance().reference.child("profile_pictures/$uid.jpg")
            isUploading = true
            storageRef.putFile(uri)
                .addOnSuccessListener {
                    storageRef.downloadUrl
                        .addOnSuccessListener { url ->
                            val freshUrl = url.toString() + "?t=${System.currentTimeMillis()}"
                            db.collection("users").document(uid)
                                .set(mapOf("photoUrl" to freshUrl), SetOptions.merge())
                                .addOnSuccessListener {
                                    photoUrl = freshUrl
                                    isUploading = false
                                }
                                .addOnFailureListener {
                                    it.printStackTrace()
                                    isUploading = false
                                }
                        }
                        .addOnFailureListener {
                            it.printStackTrace()
                            isUploading = false
                        }
                }
                .addOnFailureListener {
                    it.printStackTrace()
                    isUploading = false
                }
        }
    }

    // Real-time listener za podatke
    LaunchedEffect(uid) {
        if (uid != null) {
            db.collection("users").document(uid)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        ime = snapshot.getString("ime") ?: ""
                        prezime = snapshot.getString("prezime") ?: ""
                        telefon = snapshot.getString("telefon") ?: ""
                        photoUrl = snapshot.getString("photoUrl")
                    }
                }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Nazad", tint = Color(0xFF42A5F5))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
            Box(
                modifier = Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF90CAF9))
                    .clickable { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
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
                    !photoUrl.isNullOrBlank() -> Image(
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
                Text(text = "$ime $prezime", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                if (telefon.isNotBlank()) {
                    Text(text = "Telefon: $telefon", fontSize = 16.sp, color = Color.Gray)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Icon(imageVector = Icons.Default.Email, contentDescription = "Email", tint = Color(0xFF42A5F5))
            Spacer(Modifier.width(8.dp))
            Text(userEmail, fontSize = 16.sp)
        }

        if (telefon.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
                Icon(imageVector = Icons.Default.Phone, contentDescription = "Telefon", tint = Color(0xFF42A5F5))
                Spacer(Modifier.width(8.dp))
                Text(telefon, fontSize = 16.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onLogout,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF42A5F5), contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Logout", fontSize = 18.sp)
        }
    }
}
