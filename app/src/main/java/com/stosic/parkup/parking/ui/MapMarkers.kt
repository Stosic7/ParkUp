package com.stosic.parkup.parking.ui

import androidx.compose.runtime.Composable
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.stosic.parkup.parking.data.ParkingSpot

@Composable
fun ParkingMarker(item: ParkingSpot, onClick: (ParkingSpot) -> Unit) {
    val pos = LatLng(item.lat, item.lng)
    val colorHue = if (item.isActive) BitmapDescriptorFactory.HUE_GREEN else BitmapDescriptorFactory.HUE_RED
    Marker(
        state = MarkerState(position = pos),
        title = item.title,
        snippet = if (item.isActive) "Available" else "Reserved",
        onClick = {
            onClick(item)
            true
        },
        icon = BitmapDescriptorFactory.defaultMarker(colorHue)
    )
}
