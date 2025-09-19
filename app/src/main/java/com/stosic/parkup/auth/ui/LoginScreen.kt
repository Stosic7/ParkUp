package com.stosic.parkup.auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(
    onLoginClick: (email: String, password: String) -> Unit,
    onForgotPassword: (email: String) -> Unit,
    onNavigateToRegister: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val canSubmit = email.isNotBlank() && password.length >= 6

    // boje
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
        // Logo gore levo
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 12.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, Outline, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("P", color = Dark, fontSize = 28.sp, fontWeight = FontWeight.Black)
        }

        // Glavni sadržaj
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Kartica
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(2.dp, Outline, RoundedCornerShape(14.dp))
                    .background(Blue.copy(alpha = 0f))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Sign In",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Dark
                )

                Spacer(Modifier.height(12.dp))

                LabeledField(
                    label = "Email",
                    value = email,
                    onChange = { email = it },
                    placeholder = "Enter email address",
                    container = BlueField,
                    outline = Outline,
                    textColor = Dark
                )

                LabeledField(
                    label = "Password",
                    value = password,
                    onChange = { password = it },
                    placeholder = "Enter password",
                    isPassword = true,
                    container = BlueField,
                    outline = Outline,
                    textColor = Dark
                )

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = { onForgotPassword(email) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Forgot password?", color = Outline)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Dugme ispod kartice
            Button(
                onClick = { onLoginClick(email, password) },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF262626),
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 10.dp,
                    pressedElevation = 14.dp
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Log In", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }

            Spacer(Modifier.height(12.dp))

            // Tekst ispod dugmeta
            TextButton(onClick = onNavigateToRegister) {
                Text("Don't have an account? Sign up.", color = Outline)
            }
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
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
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

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    LoginScreen(
        onLoginClick = { _, _ -> },
        onForgotPassword = {},
        onNavigateToRegister = {}
    )
}
