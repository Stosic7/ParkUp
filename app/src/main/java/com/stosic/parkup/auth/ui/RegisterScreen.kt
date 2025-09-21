package com.stosic.parkup.auth.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RegisterScreen(
    onRegisterClick: (email: String, password: String, ime: String, prezime: String, telefon: String, photoUri: Uri?) -> Unit,
    onBack: () -> Unit = {}
) {
    var ime by remember { mutableStateOf("") }
    var prezime by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var telefon by remember { mutableStateOf("") }

    val canSubmit = ime.isNotBlank() &&
            prezime.isNotBlank() &&
            email.isNotBlank() &&
            password.length >= 6 &&
            telefon.isNotBlank()

    val Blue = Color(0xFF42A5F5)
    val BlueField = Color(0xFF90CAF9)
    val Dark = Color(0xFF2B2B2B)
    val Outline = Color(0xFFFFFFFF)

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

            // umesto profilne slike samo naslov
            Text(
                "Sign Up",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Spacer(Modifier.height(16.dp))

            // input polja
            LabeledField("Name", ime, { ime = it }, "Enter your name", container = BlueField, outline = Outline, textColor = Dark)
            LabeledField("Last Name", prezime, { prezime = it }, "Enter your last name", container = BlueField, outline = Outline, textColor = Dark)
            LabeledField("Email", email, { email = it }, "Enter email address", container = BlueField, outline = Outline, textColor = Dark)
            LabeledField("Password", password, { password = it }, "Enter password", isPassword = true, container = BlueField, outline = Outline, textColor = Dark)
            LabeledField("Phone Number", telefon, { telefon = it }, "Enter phone number", container = BlueField, outline = Outline, textColor = Dark)

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onRegisterClick(email, password, ime, prezime, telefon, null) }, // photoUri uvek null
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262626), contentColor = Color.White),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 10.dp, pressedElevation = 14.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
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
