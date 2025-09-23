package com.stosic.parkup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.stosic.parkup.auth.ui.LoginScreen
import com.stosic.parkup.auth.ui.RegisterScreen
import com.stosic.parkup.auth.ui.StartingScreen
import com.stosic.parkup.home.HomeContent
import com.stosic.parkup.ui.CustomPopup

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
    val db = FirebaseFirestore.getInstance()

    var screen by remember { mutableStateOf(if (auth.currentUser != null) "home" else "start") }
    var message by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var userData by remember { mutableStateOf<Map<String, String>?>(null) }

    if (showDialog && message != null) {
        CustomPopup(message = message!!, onDismiss = { showDialog = false })
    }

    // 📌 Centralni snapshot listener za user dokument
    var userDocReg by remember { mutableStateOf<com.google.firebase.firestore.ListenerRegistration?>(null) }

    DisposableEffect(auth.currentUser?.uid) {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            userDocReg = db.collection("users").document(uid)
                .addSnapshotListener { doc, _ ->
                    if (doc != null && doc.exists()) {
                        userData = mapOf(
                            "ime" to (doc.getString("ime") ?: ""),
                            "prezime" to (doc.getString("prezime") ?: ""),
                            "telefon" to (doc.getString("telefon") ?: ""),
                            "email" to (doc.getString("email") ?: ""),
                            "photoUrl" to (doc.getString("photoUrl") ?: "")
                        )
                        println("userData updated: $userData")
                    }
                }
        }
        onDispose {
            userDocReg?.remove()
            userDocReg = null
        }
    }

    when (screen) {
        "start" -> StartingScreen(
            onLoginClick = { screen = "login" },
            onRegisterClick = { screen = "register" }
        )

        "login" -> LoginScreen(
            onLoginClick = { email, password ->
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            screen = "home"
                            message = "Login successful"
                            showDialog = true
                        } else {
                            message = "Login failed: ${task.exception?.message}"
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

        "register" -> RegisterScreen(
            onRegisterClick = { email, pass, ime, prezime, telefon, photoUri ->
                auth.createUserWithEmailAndPassword(email, pass)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val uid = auth.currentUser?.uid
                            if (uid != null) {
                                val userDataMap = hashMapOf(
                                    "ime" to ime,
                                    "prezime" to prezime,
                                    "telefon" to telefon,
                                    "email" to email
                                )

                                db.collection("users").document(uid).set(userDataMap)
                                    .addOnSuccessListener {
                                        screen = "home"
                                        message = "Registration successful"
                                        showDialog = true

                                        if (photoUri != null) {
                                            val storageRef = FirebaseStorage.getInstance()
                                                .reference.child("profile_pictures/$uid.jpg")
                                            storageRef.putFile(photoUri)
                                                .addOnSuccessListener {
                                                    storageRef.downloadUrl
                                                        .addOnSuccessListener { url ->
                                                            db.collection("users").document(uid)
                                                                .update("photoUrl", url.toString())
                                                        }
                                                }
                                        }
                                    }
                                    .addOnFailureListener {
                                        message = "Failed to save user data: ${it.message}"
                                        showDialog = true
                                    }
                            }
                        } else {
                            message = "Registration failed: ${task.exception?.message}"
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

        "home" -> HomeContent(
            userEmail = auth.currentUser?.email ?: "Unknown",
            userData = userData,
            onLogout = {
                auth.signOut()
                screen = "start"
                userData = null
            }
        )
    }
}
