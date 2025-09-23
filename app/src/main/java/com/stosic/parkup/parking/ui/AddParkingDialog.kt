package com.stosic.parkup.parking.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import com.stosic.parkup.parking.data.ParkingRepository
import com.stosic.parkup.R

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

                picked?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Koordinate: ${"%.6f".format(it.lat)}, ${"%.6f".format(it.lng)}",
                        fontSize = 12.sp,
                        color = Color(0xFF5E6A7D)
                    )
                }

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = Color(0xFFB71C1C), fontSize = 12.sp)
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
                            capacity = capVal
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

// --- UI helpers & REST (isti kao ranije) ---

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
            Text(item.secondaryText, fontSize = 12.sp, color = Color(0xFF5E6A7D))
        }
    }
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

private suspend fun geocodeSuggestions(query: String, token: String): List<AddressSuggestion> {
    if (token.isBlank()) return emptyList()

    val encoded = URLEncoder.encode(query, "UTF-8")
    val bbox = "21.78,43.26,22.05,43.40" // okvir Niša
    val url = "https://api.mapbox.com/geocoding/v5/mapbox.places/$encoded.json" +
            "?autocomplete=true&limit=5&country=RS&bbox=$bbox&access_token=$token"

    return withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            conn.inputStream.bufferedReader().use { br ->
                val raw = br.readText()
                parseGeocodingResponse(raw)
            }
        } finally {
            conn.disconnect()
        }
    }
}

private fun parseGeocodingResponse(raw: String): List<AddressSuggestion> {
    val root = JSONObject(raw)
    val features: JSONArray = root.optJSONArray("features") ?: JSONArray()
    val out = ArrayList<AddressSuggestion>(features.length())
    for (i in 0 until features.length()) {
        val f = features.optJSONObject(i) ?: continue
        val placeName = f.optString("place_name")
        val text = f.optString("text")
        val ctxArr = f.optJSONArray("context") ?: JSONArray()
        var secondary = ""
        if (ctxArr.length() > 0) {
            val names = mutableListOf<String>()
            for (j in 0 until ctxArr.length()) {
                val obj = ctxArr.optJSONObject(j) ?: continue
                val nm = obj.optString("text")
                if (nm.isNotBlank()) names.add(nm)
            }
            secondary = names.joinToString(", ")
        }
        val center = f.optJSONArray("center") ?: continue
        val lng = center.optDouble(0)
        val lat = center.optDouble(1)
        out.add(
            AddressSuggestion(
                primaryText = text.ifBlank { placeName },
                secondaryText = secondary,
                fullAddress = placeName,
                lat = lat,
                lng = lng
            )
        )
    }
    return out
}
