package com.stosic.parkup.auth.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Patterns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream

@Composable
fun RegisterScreen(
    onRegisterClick: (
        email: String,
        password: String,
        ime: String,
        prezime: String,
        telefon: String,
        photoUri: Uri?
    ) -> Unit,
    onBack: () -> Unit = {}
) {
    var ime by remember { mutableStateOf("") }
    var prezime by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var telefon by remember { mutableStateOf("") }
    fun isValidEmail(e: String): Boolean = Patterns.EMAIL_ADDRESS.matcher(e).matches()
    val emailValid by remember(email) { mutableStateOf(isValidEmail(email)) }


    val canSubmit = ime.isNotBlank() &&
            prezime.isNotBlank() &&
            email.isNotBlank() && emailValid &&
            password.length >= 6 &&
            telefon.isNotBlank()

    val Blue = Color(0xFF42A5F5)
    val BlueField = Color(0xFF90CAF9)
    val Dark = Color(0xFF2B2B2B)
    val Outline = Color(0xFFFFFFFF)

    val ctx = LocalContext.current

    // držimo odabranu fotku za preview
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    // Helper: enkodiraj uri -> Base64 thumbnail (256px, 80% JPEG)
    fun uriToBase64Thumbnail(context: Context, uri: Uri, maxSizePx: Int = 256, quality: Int = 80): String {
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.createSource(context.contentResolver, uri)
        } else {
            @Suppress("DEPRECATION")
            ImageDecoder.createSource(context.contentResolver, uri)
        }
        val original = ImageDecoder.decodeBitmap(source)
        val scale = minOf(maxSizePx / original.width.toFloat(), maxSizePx / original.height.toFloat(), 1f)
        val w = (original.width * scale).toInt().coerceAtLeast(1)
        val h = (original.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(original, w, h, true)
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    // GALLERY picker
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) photoUri = uri
    }

    // CAMERA: TakePicturePreview -> Bitmap -> snimi u MediaStore da dobije Uri (za preview), ali ćemo i Base64
    val takePhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) {
            val resolver = ctx.contentResolver
            val name = "profile_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ParkUp")
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                photoUri = uri
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Blue)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // logo
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(2.dp, Outline, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("P", color = Dark, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Sign Up",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Spacer(Modifier.height(12.dp))

            // Profilna preview + akcije
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(BlueField)
                        .clickable { pickImage.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (photoUri != null) {
                        AsyncImage(
                            model = photoUri,
                            contentDescription = "Profilna",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("IMG", color = Dark, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Button(
                    onClick = { pickImage.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262626), contentColor = Color.White)
                ) { Text("Galerija") }

                Button(
                    onClick = { takePhoto.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262626), contentColor = Color.White)
                ) { Text("Kamera") }
            }

            Spacer(Modifier.height(16.dp))

            // input polja
            LabeledField("Name", ime, { ime = it }, "Enter your name", container = BlueField, outline = Outline, textColor = Dark)
            LabeledField("Last Name", prezime, { prezime = it }, "Enter your last name", container = BlueField, outline = Outline, textColor = Dark)
            LabeledField("Email", email, { email = it }, "Enter email address", container = BlueField, outline = Outline, textColor = Dark)
            if (email.isNotBlank() && !emailValid) {
                Text(
                    "Nevažeći email format (mora biti nesto@domen.tld).",
                    color = Color(0xFFFFCACA),
                    fontSize = 12.sp
                )
            }
            LabeledField("Password", password, { password = it }, "Enter password", isPassword = true, container = BlueField, outline = Outline, textColor = Dark)
            LabeledField("Phone Number", telefon, { telefon = it }, "Enter phone number", container = BlueField, outline = Outline, textColor = Dark)

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    // Ako je korisnik izabrao sliku, enkodiraj i zapamti privremeno u SharedPreferences.
                    // ProfileScreen će na prvom otvaranju prebaciti u Firestore i obrisati pending.
                    val prefs = ctx.getSharedPreferences("parkup_prefs", Context.MODE_PRIVATE)
                    if (photoUri != null) {
                        runCatching {
                            val b64 = uriToBase64Thumbnail(ctx, photoUri!!)
                            prefs.edit().putString("pending_profile_b64", b64).apply()
                        }
                    } else {
                        prefs.edit().remove("pending_profile_b64").apply()
                    }
                    // Bitno: pošalji NULL za photoUri da MainActivity NE pokreće Firebase Storage.
                    onRegisterClick(email, password, ime, prezime, telefon, null)
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262626), contentColor = Color.White),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 10.dp, pressedElevation = 14.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Sign Up Now", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onBack) {
                Text("Have an account? Login.", color = Outline)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    container: Color,
    outline: Color,
    textColor: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = textColor, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            placeholder = { Text(placeholder, color = outline.copy(alpha = 0.8f)) },
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            shape = RoundedCornerShape(10.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = container,
                unfocusedContainerColor = container,
                disabledContainerColor = container,
                focusedIndicatorColor = outline,
                unfocusedIndicatorColor = outline,
                cursorColor = textColor
            ),
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
    }
}
