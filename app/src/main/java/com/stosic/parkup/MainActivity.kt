package com.stosic.parkup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.firebase.auth.FirebaseAuth
import com.stosic.parkup.auth.ui.LoginScreen
import com.stosic.parkup.auth.ui.RegisterScreen
import com.stosic.parkup.auth.ui.StartingScreen
import com.stosic.parkup.home.HomeContent
import com.stosic.parkup.ui.CustomPopup
import com.stosic.parkup.home.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuthHost()
        }
    }
}

@Composable
fun AuthHost() {
    val auth = FirebaseAuth.getInstance()

    var screen by remember {
        mutableStateOf(
            if (auth.currentUser != null) "home" else "start"
        )
    }

    var message by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog && message != null) {
        CustomPopup(
            message = message!!,
            onDismiss = { showDialog = false }
        )
    }

    when (screen) {
        "start" -> {
            StartingScreen(
                onLoginClick = { screen = "login" },
                onRegisterClick = { screen = "register" }
            )
        }

        "login" -> {
            LoginScreen(
                onLoginClick = { email, password ->
                    if (email.isBlank() || password.isBlank()) {
                        message = "Email and password must not be empty"
                        showDialog = true
                    } else {
                        auth.signInWithEmailAndPassword(email, password)
                            .addOnCompleteListener { task ->
                                message = if (task.isSuccessful) {
                                    screen = "home"
                                    "Login successful: ${auth.currentUser?.email}"
                                } else {
                                    "Login failed: ${task.exception?.message}"
                                }
                                showDialog = true
                            }
                    }
                },
                onForgotPassword = { email ->
                    if (email.isNotBlank()) {
                        auth.sendPasswordResetEmail(email)
                            .addOnCompleteListener { task ->
                                message = if (task.isSuccessful) {
                                    "Reset link sent to $email"
                                } else {
                                    "Failed to send reset link: ${task.exception?.message}"
                                }
                                showDialog = true
                            }
                    } else {
                        message = "Enter your email first!"
                        showDialog = true
                    }
                },
                onNavigateToRegister = {
                    screen = "register"
                    message = null
                    showDialog = false
                }
            )
        }

        "register" -> {
            RegisterScreen(
                onPickPhoto = { /* TODO: upload slika kasnije */ },
                onRegisterClick = { email, pass, ime, prezime, telefon ->
                    if (email.isBlank() || pass.isBlank()) {
                        message = "Email and password must not be empty"
                        showDialog = true
                    } else {
                        auth.createUserWithEmailAndPassword(email, pass)
                            .addOnCompleteListener { task ->
                                message = if (task.isSuccessful) {
                                    screen = "home"
                                    "Registration successful: ${auth.currentUser?.email}"
                                } else {
                                    "Registration failed: ${task.exception?.message}"
                                }
                                showDialog = true
                            }
                    }
                },
                onBack = {
                    screen = "login"
                    message = null
                    showDialog = false
                }
            )
        }

        "home" -> {
            HomeContent(
                userEmail = auth.currentUser?.email ?: "Unknown",
                onLogout = {
                    auth.signOut()
                    screen = "start"
                }
            )
        }
    }
}
