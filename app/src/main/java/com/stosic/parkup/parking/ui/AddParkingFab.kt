package com.stosic.parkup.parking.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun AddParkingFab(
    onClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnim"
    )
    val popScale by animateFloatAsState(
        targetValue = if (pressed) 1.12f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "popScale"
    )
    val rotation by animateFloatAsState(
        targetValue = if (pressed) 45f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "rotation"
    )

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .size(64.dp)
                .scale(pulse * popScale)
                .rotate(rotation)
                .clickable {
                    pressed = true
                    onClicked()
                    pressed = false
                },
            shape = CircleShape,
            color = Color(0xFF42A5F5),
            contentColor = Color.White,
            shadowElevation = 6.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = "Dodaj parking")
            }
        }

        Box(
            Modifier
                .matchParentSize()
                .scale(1.2f * pulse)
                .background(Color(0xFF42A5F5).copy(alpha = 0.08f), CircleShape)
        )
    }
}
