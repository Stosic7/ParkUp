package com.stosic.parkup.auth.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.stosic.parkup.R

@Composable
fun StartingScreen(
    onLoginClick: () -> Unit,
    onRegisterClick: () -> Unit
) {
    val Blue = Color(0xFF42A5F5)
    val Green = Color(0xFF43A047)

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Blue)
                .padding(padding)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                // jednostavna responsivnost
                val compact = maxWidth < 360.dp
                val titleSize = if (compact) 56.sp else 84.sp   // umesto 103.sp

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "ParkUp",
                        fontSize = titleSize,
                        fontWeight = FontWeight.ExtraBold,
                        fontStyle = FontStyle.Italic,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(12.dp))

                    // Bez fiksnih 500dp: ograniči maksimalnu visinu i čuvaj odnos
                    Image(
                        painter = painterResource(id = R.drawable.car_parking),
                        contentDescription = "Car Parking",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = if (compact) 220.dp else 320.dp)
                    )

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = onLoginClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Green,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .border(2.dp, Color.White, RoundedCornerShape(14.dp))
                    ) {
                        Text("Login", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onRegisterClick,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White,
                            contentColor = Blue
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .border(2.dp, Blue, RoundedCornerShape(14.dp))
                    ) {
                        Text("Register", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun StartingScreenPreview() {
    StartingScreen(
        onLoginClick = {},
        onRegisterClick = {}
    )
}
