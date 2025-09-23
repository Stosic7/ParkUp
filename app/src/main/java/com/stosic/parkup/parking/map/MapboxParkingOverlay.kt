package com.stosic.parkup.parking.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.stosic.parkup.parking.data.ParkingSpot

object MapboxParkingOverlay {

    private var reg: ListenerRegistration? = null
    private var mgr: PointAnnotationManager? = null

    private val green by lazy { createCircleBitmap(0xFF2E7D32.toInt()) }
    private val red by lazy { createCircleBitmap(0xFFC62828.toInt()) }

    fun install(
        mapView: MapView,
        onMarkerClick: ((ParkingSpot) -> Unit)? = null
    ) {
        if (mgr == null) {
            mgr = mapView.annotations.createPointAnnotationManager(AnnotationConfig())
        }
        reg?.remove()
        reg = FirebaseFirestore.getInstance()
            .collection("parkings")
            .addSnapshotListener { qs, _ ->
                val list = qs?.documents?.mapNotNull { it.toObject(ParkingSpot::class.java) }.orEmpty()
                render(list, onMarkerClick)
            }
    }

    fun cleanup() {
        reg?.remove(); reg = null
        mgr?.deleteAll(); mgr = null
    }

    private fun render(
        items: List<ParkingSpot>,
        onClick: ((ParkingSpot) -> Unit)?
    ) {
        val m = mgr ?: return
        m.deleteAll()
        if (items.isEmpty()) return

        val opts = items.map { p ->
            // Boja: CRVENO ako nema slobodnih mesta (availableSlots <= 0), inače ZELENO
            val available = p.availableSlots
            val icon = if (available <= 0L) red else green

            val label = when {
                p.title.isNotBlank() -> p.title
                p.address.isNotBlank() -> p.address
                else -> "Parking"
            }

            PointAnnotationOptions()
                .withPoint(Point.fromLngLat(p.lng, p.lat))
                .withIconImage(icon)
                .withIconSize(1.0)
                .withTextField(label)
                .withTextOffset(listOf(0.0, 2.0))
        }
        val created = m.create(opts)

        if (onClick != null) {
            created.forEachIndexed { idx, anno ->
                m.addClickListener { clicked ->
                    if (clicked.id == anno.id) {
                        onClick(items[idx]); true
                    } else false
                }
            }
        }
    }

    private fun createCircleBitmap(color: Int, size: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val r = size / 2f
        c.drawCircle(r, r, r, p)
        return bmp
    }
}
