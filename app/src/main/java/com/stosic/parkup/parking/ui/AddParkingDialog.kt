package com.stosic.parkup.parking.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stosic.parkup.R
import com.stosic.parkup.parking.data.ParkingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Composable
fun AddParkingDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var capacity by remember { mutableStateOf("") }

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var picked by remember { mutableStateOf<AddressSuggestion?>(null) }

    // --- NOVO: slika parking mesta (opciono) ---
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var photoBase64 by remember { mutableStateOf<String?>(null) }

    // --- NOVO: dodatna polja ---
    var placeType by remember { mutableStateOf("street") }      // "street" | "garage"
    var isDisabledSpot by remember { mutableStateOf(false) }    // da li je mesto za invalide
    var zone by remember { mutableStateOf("green") }            // "green" | "red" | "extra"

    fun uriToBase64(uri: Uri, maxSize: Int = 1024, quality: Int = 85): String {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri))
        } else {
            ctx.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                BitmapFactory.decodeStream(input)
            }
        }
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
        if (uri != null) {
            photoUri = uri
            photoBase64 = runCatching { uriToBase64(uri) }.getOrNull()
        }
    }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            photoBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            // za preview formiramo privremeni uri u MediaStore
            val name = "parking_${System.currentTimeMillis()}.jpg"
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ParkUp")
                }
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                photoUri = uri
            }
        }
    }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val mapboxToken by remember { mutableStateOf(readMapboxToken(ctx)) }

    LaunchedEffect(query) {
        picked = null
        if (query.length < 3) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        val q = query
        val token = mapboxToken
        suggestions = withContext(Dispatchers.IO) {
            runCatching { geocodeSuggestions(q, token) }.getOrElse { emptyList() }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Dodaj parking") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Adresa (Niš)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (picked == null && suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                    ) {
                        items(suggestions) { s ->
                            SuggestionRow(s) { picked = it; query = it.fullAddress }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Naziv parking mesta") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Cena / h (RSD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = capacity,
                    onValueChange = { capacity = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Kapacitet (broj mesta)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // --- NOVO: Tip lokacije (ulica / garaža) ---
                Text("Tip lokacije", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip(
                        selected = placeType == "street",
                        label = "Ulica"
                    ) { placeType = "street" }
                    SelectChip(
                        selected = placeType == "garage",
                        label = "Garaža"
                    ) { placeType = "garage" }
                }

                Spacer(Modifier.height(10.dp))

                // --- NOVO: Da li je mesto za invalide ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isDisabledSpot, onCheckedChange = { isDisabledSpot = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Mesto za invalide")
                }

                Spacer(Modifier.height(10.dp))

                // --- NOVO: Zona (zelena / crvena / extra) preko boja ---
                Text("Zona", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZoneChip(
                        text = "Zelena",
                        color = Color(0xFF2E7D32),
                        selected = zone == "green"
                    ) { zone = "green" }
                    ZoneChip(
                        text = "Crvena",
                        color = Color(0xFFC62828),
                        selected = zone == "red"
                    ) { zone = "red" }
                    ZoneChip(
                        text = "Extra",
                        color = Color(0xFFFFC107),
                        selected = zone == "extra"
                    ) { zone = "extra" }
                }

                Spacer(Modifier.height(12.dp))

                // --- Slika ---
                Text("Slika parking mesta (opciono)", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text("Galerija")
                    }
                    OutlinedButton(onClick = { takePhoto.launch(null) }) {
                        Text("Kamera")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        photoUri != null -> {
                            AsyncImage(
                                model = photoUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .heightIn(max = 180.dp)
                                    .fillMaxWidth()
                            )
                        }
                        photoBase64 != null -> {
                            val bytes = remember(photoBase64) { Base64.decode(photoBase64, Base64.DEFAULT) }
                            val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .heightIn(max = 180.dp)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                picked?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Koordinate: ${"%.6f".format(it.lat)}, ${"%.6f".format(it.lng)}",
                        fontSize = 12.sp
                    )
                }

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pick = picked
                    if (pick == null) { error = "Izaberi validnu adresu iz liste."; return@TextButton }
                    if (title.isBlank()) { error = "Unesi naziv."; return@TextButton }
                    val capVal = capacity.toLongOrNull() ?: -1L
                    if (capVal < 0L) { error = "Kapacitet mora biti broj (0 ili više)."; return@TextButton }

                    busy = true; error = null
                    scope.launch {
                        val p = price.toLongOrNull() ?: 0L
                        val r = ParkingRepository.addParkingSpot(
                            title = title.trim(),
                            address = pick.fullAddress,
                            lat = pick.lat,
                            lng = pick.lng,
                            pricePerHour = p,
                            capacity = capVal,
                            photoBase64 = photoBase64,
                            // --- NOVO: prosleđujemo dodatna polja ---
                            placeType = placeType,           // "street" | "garage"
                            isDisabledSpot = isDisabledSpot, // true | false
                            zone = zone                      // "green" | "red" | "extra"
                        )
                        busy = false
                        if (r.isSuccess) onSaved() else {
                            error = r.exceptionOrNull()?.message ?: "Greška pri upisu."
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
        }
    )
}

// --- UI helpers & REST (kao ranije) ---

@Composable
private fun SuggestionRow(
    item: AddressSuggestion,
    onPick: (AddressSuggestion) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(item) }
            .padding(vertical = 8.dp)
    ) {
        Text(item.primaryText, fontWeight = FontWeight.SemiBold)
        if (item.secondaryText.isNotBlank()) {
            Text(item.secondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SelectChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun ZoneChip(
    text: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )
        }
    )
}

private data class AddressSuggestion(
    val primaryText: String,
    val secondaryText: String,
    val fullAddress: String,
    val lat: Double,
    val lng: Double
)

private fun readMapboxToken(ctx: android.content.Context): String {
    runCatching {
        val ai = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        val md = ai.metaData
        val t1 = md.getString("MAPBOX_ACCESS_TOKEN")
        if (!t1.isNullOrBlank()) return t1
        val t2 = md.getString("com.mapbox.token")
        if (!t2.isNullOrBlank()) return t2
    }
    return runCatching { ctx.getString(R.string.mapbox_access_token) }.getOrDefault("")
}

private suspend fun geocodeSuggestions(query: String, token: String): List<AddressSuggestion> =
    withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://api.mapbox.com/geocoding/v5/mapbox.places/$q.json?types=address&limit=5&language=sr&autocomplete=true&access_token=$token&proximity=21.90,43.30")
        val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 6000 }
        conn.inputStream.use { inp ->
            val raw = inp.readBytes().decodeToString()
            val jo = JSONObject(raw)
            val feats = jo.optJSONArray("features") ?: JSONArray()
            (0 until feats.length()).mapNotNull { i ->
                val f = feats.getJSONObject(i)
                val place = f.optString("place_name_sr", f.optString("place_name", ""))
                val center = f.optJSONArray("center") ?: return@mapNotNull null
                val lng = center.optDouble(0); val lat = center.optDouble(1)
                val text = f.optString("text_sr", f.optString("text", ""))
                AddressSuggestion(primaryText = text, secondaryText = place, fullAddress = place, lat = lat, lng = lng)
            }
        }
    }
