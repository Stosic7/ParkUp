package com.stosic.parkup.parking.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.extension.style.image.addImage
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.stosic.parkup.parking.data.ParkingSpot
import kotlin.math.*

// --- DODATO: da bismo čitali moju lokaciju i radijus iz users/{uid}
import com.google.firebase.auth.FirebaseAuth

/**
 * Stabilan overlay bez annotation plugina i bez queryRenderedFeatures klika.
 * - DVA GeoJSON izvora (GREEN/RED) + DVA SymbolLayer-a
 * - Klik: računa najbliži ParkingSpot u malom radijusu (30 m) i vraća ga.
 * - DODATO: filtriranje parkinga po `users/{uid}.searchRadius` oko `users/{uid}.lat/lng`
 */
object MapboxParkingOverlay {

    private const val SRC_GREEN = "parkings-src-green"
    private const val SRC_RED   = "parkings-src-red"
    private const val LYR_GREEN = "parkings-lyr-green"
    private const val LYR_RED   = "parkings-lyr-red"
    private const val IMG_GREEN = "parking-green"
    private const val IMG_RED   = "parking-red"

    private var reg: ListenerRegistration? = null
    private var currentSpots: List<ParkingSpot> = emptyList()

    private var srcGreenRef: GeoJsonSource? = null
    private var srcRedRef: GeoJsonSource? = null

    private var clickListener: OnMapClickListener? = null

    // --- DODATO: realtime moja lokacija + radijus ---
    private var userReg: ListenerRegistration? = null
    private var myLat: Double? = null
    private var myLng: Double? = null
    private var searchRadiusMeters: Int = 0 // 0 = isključeno

    fun install(
        mapView: MapView,
        onMarkerClick: ((ParkingSpot) -> Unit)? = null
    ) {
        val mapboxMap = mapView.getMapboxMap()
        val style = mapboxMap.getStyle() ?: return

        // Ikonice — overwrite je bezbedan
        style.addImage(IMG_GREEN, createCircleBitmap(Color.parseColor("#FF2E7D32")))
        style.addImage(IMG_RED,   createCircleBitmap(Color.parseColor("#FFC62828")))

        // Reset izvora/slojeva (čist state posle navigacije)
        runCatching { style.removeStyleLayer(LYR_GREEN) }
        runCatching { style.removeStyleLayer(LYR_RED) }
        runCatching { style.removeStyleSource(SRC_GREEN) }
        runCatching { style.removeStyleSource(SRC_RED) }

        val srcGreen = geoJsonSource(SRC_GREEN) {
            featureCollection(FeatureCollection.fromFeatures(emptyList()))
        }
        val srcRed = geoJsonSource(SRC_RED) {
            featureCollection(FeatureCollection.fromFeatures(emptyList()))
        }
        style.addSource(srcGreen); srcGreenRef = srcGreen
        style.addSource(srcRed);   srcRedRef = srcRed

        val layerGreen: SymbolLayer = symbolLayer(LYR_GREEN, SRC_GREEN) {
            iconAllowOverlap(true)
            iconIgnorePlacement(true)
            iconAnchor(IconAnchor.BOTTOM)
            iconImage(IMG_GREEN)
            textField("{label}")
            textOffset(listOf(0.0, 1.2))
            textSize(11.0)
        }
        val layerRed: SymbolLayer = symbolLayer(LYR_RED, SRC_RED) {
            iconAllowOverlap(true)
            iconIgnorePlacement(true)
            iconAnchor(IconAnchor.BOTTOM)
            iconImage(IMG_RED)
            textField("{label}")
            textOffset(listOf(0.0, 1.2))
            textSize(11.0)
        }
        style.addLayer(layerGreen)
        style.addLayer(layerRed)

        // Firestore realtime → puni izvore (sirovi spisak)
        reg?.remove()
        reg = FirebaseFirestore.getInstance()
            .collection("parkings")
            .addSnapshotListener { qs, _ ->
                val list = qs?.documents?.mapNotNull { it.toObject(ParkingSpot::class.java) }.orEmpty()
                currentSpots = list
                updateSources(list) // <-- filtriranje se radi unutar updateSources
            }

        // --- DODATO: slušaj users/{uid} za lat/lng + searchRadius ---
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        userReg?.remove()
        if (uid != null) {
            userReg = FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .addSnapshotListener { doc, _ ->
                    myLat = doc?.getDouble("lat")
                    myLng = doc?.getDouble("lng")
                    searchRadiusMeters = (doc?.getLong("searchRadius") ?: 0L).toInt()
                    updateSources(currentSpots)
                }
        }

        // Klik listener: najbliži spot u 30 m
        clickListener?.let { mapView.gestures.removeOnMapClickListener(it) }
        clickListener = OnMapClickListener { lngLat ->
            val spot = nearestSpot(currentSpots, lngLat.latitude(), lngLat.longitude(), 30.0)
            if (spot != null) {
                onMarkerClick?.invoke(spot)
                // ne konzumiramo gest (false), da mapa i dalje radi normalno
            }
            false
        }
        mapView.gestures.addOnMapClickListener(clickListener!!)
    }

    fun cleanup(mapView: MapView? = null) {
        reg?.remove(); reg = null
        userReg?.remove(); userReg = null
        if (mapView != null && clickListener != null) {
            mapView.gestures.removeOnMapClickListener(clickListener!!)
        }
        clickListener = null
        srcGreenRef = null
        srcRedRef = null
    }

    // --- Helpers ---

    private fun updateSources(spots: List<ParkingSpot>) {
        val greenFeats = ArrayList<Feature>()
        val redFeats = ArrayList<Feature>()

        // --- DODATO: primeni filtriranje po radijusu ako imamo centar i radius > 0 ---
        val filtered = if (myLat != null && myLng != null && searchRadiusMeters > 0) {
            spots.filter { p ->
                haversineMeters(myLat!!, myLng!!, p.lat, p.lng) <= searchRadiusMeters
            }
        } else {
            spots
        }

        for (p in filtered) {
            val f = Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat))
            val label = when {
                p.title.isNotBlank() -> p.title
                p.address.isNotBlank() -> p.address
                else -> "Parking"
            }
            f.addStringProperty("label", label)

            if (p.availableSlots <= 0L) redFeats.add(f) else greenFeats.add(f)
        }

        srcGreenRef?.featureCollection(FeatureCollection.fromFeatures(greenFeats))
        srcRedRef?.featureCollection(FeatureCollection.fromFeatures(redFeats))
    }

    private fun createCircleBitmap(color: Int, size: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val r = size / 2f
        c.drawCircle(r, r, r, p)
        return bmp
    }

    private fun nearestSpot(
        spots: List<ParkingSpot>,
        lat: Double,
        lng: Double,
        withinMeters: Double
    ): ParkingSpot? {
        var best: ParkingSpot? = null
        var bestDist = Double.MAX_VALUE
        for (p in spots) {
            val d = haversineMeters(lat, lng, p.lat, p.lng)
            if (d < bestDist) {
                bestDist = d; best = p
            }
        }
        return if (best != null && bestDist <= withinMeters) best else null
    }

    private fun haversineMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}
