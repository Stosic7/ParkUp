package com.stosic.parkup.parking.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import com.google.firebase.auth.FirebaseAuth

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
    private var userReg: ListenerRegistration? = null
    private var myLat: Double? = null
    private var myLng: Double? = null
    private var searchRadiusMeters: Int = 0

    fun install(
        mapView: MapView,
        onMarkerClick: ((ParkingSpot) -> Unit)? = null
    ) {
        val mapboxMap = mapView.getMapboxMap()
        val style = mapboxMap.getStyle() ?: return

        style.addImage(IMG_GREEN, createCircleBitmap(Color.parseColor("#FF2E7D32")))
        style.addImage(IMG_RED,   createCircleBitmap(Color.parseColor("#FFC62828")))

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
            iconSize(2.35)
            textField("{label}")
            textOffset(listOf(0.0, 1.2))
            textSize(11.0)
        }
        val layerRed: SymbolLayer = symbolLayer(LYR_RED, SRC_RED) {
            iconAllowOverlap(true)
            iconIgnorePlacement(true)
            iconAnchor(IconAnchor.BOTTOM)
            iconImage(IMG_RED)
            iconSize(2.35)
            textField("{label}")
            textOffset(listOf(0.0, 1.2))
            textSize(11.0)
        }
        style.addLayer(layerGreen)
        style.addLayer(layerRed)

        reg?.remove()
        reg = FirebaseFirestore.getInstance()
            .collection("parkings")
            .addSnapshotListener { qs, _ ->
                val list = qs?.documents?.mapNotNull { it.toObject(ParkingSpot::class.java) }.orEmpty()
                currentSpots = list
                updateSources(list)
            }

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

        clickListener?.let { mapView.gestures.removeOnMapClickListener(it) }
        clickListener = OnMapClickListener { lngLat ->
            val spot = nearestSpot(currentSpots, lngLat.latitude(), lngLat.longitude(), 30.0)
            if (spot != null) {
                onMarkerClick?.invoke(spot)
            }
            false
        }
        mapView.gestures.addOnMapClickListener(clickListener!!)
    }

    private fun updateSources(spots: List<ParkingSpot>) {
        val greenFeats = ArrayList<Feature>()
        val redFeats = ArrayList<Feature>()

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

        val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#424242") }
        val poleWidth = size * 0.10f
        val poleLeft = size * 0.45f - poleWidth / 2f
        val poleRight = poleLeft + poleWidth
        val poleTop = size * 0.10f
        val poleBottom = size * 0.98f
        val poleRect = RectF(poleLeft, poleTop, poleRight, poleBottom)
        c.drawRoundRect(poleRect, poleWidth / 2f, poleWidth / 2f, polePaint)

        val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val flagTopY = size * 0.18f
        val flagMidY = size * 0.36f
        val flagRightX = size * 0.88f
        val poleX = (poleLeft + poleRight) / 2f

        val path = Path().apply {
            moveTo(poleX, flagTopY)
            lineTo(flagRightX, flagMidY)
            lineTo(poleX, flagMidY + (flagMidY - flagTopY))
            close()
        }
        c.drawPath(path, flagPaint)

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#303030") }
        c.drawCircle(poleX, poleBottom, poleWidth * 0.35f, basePaint)

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
