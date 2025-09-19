package com.stosic.parkup.auth.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun RegisterScreen(
    onPickPhoto: () -> Unit,
    onRegisterClick: (email: String, password: String, ime: String, prezime: String, telefon: String) -> Unit,
    onBack: () -> Unit = {}
) {
    var ime by remember { mutableStateOf("") }
    var prezime by remember { mutableStateOf("") }
    var telefon by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var photoSelected by remember { mutableStateOf(false) }

    val canSubmit = ime.isNotBlank() &&
            prezime.isNotBlank() &&
            telefon.isNotBlank() &&
            email.isNotBlank() &&
            password.length >= 6

    // 👉 Gradijent pozadina
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFFE0B2),
                        Color(0xFFFFCCBC),
                        Color(0xFFE65100),
                        Color(0xFFE65100),
                    )
                )
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Naslov centriran
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Text("Nazad", fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "Registracija",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Divider()

            OutlinedTextField(
                value = ime,
                onValueChange = { ime = it },
                label = { Text("Ime", fontWeight = FontWeight.Bold) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = prezime,
                onValueChange = { prezime = it },
                label = { Text("Prezime", fontWeight = FontWeight.Bold) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = telefon,
                onValueChange = { telefon = it },
                label = { Text("Telefon", fontWeight = FontWeight.Bold) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email", fontWeight = FontWeight.Bold) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Lozinka", fontWeight = FontWeight.Bold) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Checkbox(checked = showPassword, onCheckedChange = { showPassword = it })
                Text("Prikaži lozinku", fontWeight = FontWeight.Bold)
            }

            // Fotografija (opciono)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        onPickPhoto()
                        photoSelected = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFBF360C), // svetla narandžasta
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(2.dp, Color.DarkGray)
                ) {
                    Text("Odaberi fotografiju (opciono)", fontWeight = FontWeight.Bold)
                }
                Text(
                    if (photoSelected) "Fotografija dodata" else "Nije dodata",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onRegisterClick(email, password, ime, prezime, telefon) },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFB74D), // svetla narandžasta
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, Color.DarkGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Registruj se", fontWeight = FontWeight.Bold)
            }

            Text(
                text = "Popunite sva polja. Lozinka mora imati bar 6 karaktera.",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RegisterScreenPreview() {
    RegisterScreen(
        onPickPhoto = {},
        onRegisterClick = { _, _, _, _, _ -> },
        onBack = {}
    )
}
