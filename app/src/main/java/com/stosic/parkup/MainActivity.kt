package com.stosic.parkup

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.firebase.auth.FirebaseAuth
import com.stosic.parkup.core.RankAutoUpdater
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.stosic.parkup.auth.ui.LoginScreen
import com.stosic.parkup.auth.ui.RegisterScreen
import com.stosic.parkup.auth.ui.StartingScreen
import com.stosic.parkup.core.location.LocationUpdatesService
import com.stosic.parkup.home.HomeContent
import com.stosic.parkup.ui.CustomPopup

// dodatci za fullscreen splash (samo MainActivity menjamo)
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private val requestPerms = registerForActivityResult( // requesting for permission
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        maybeStartLocationService()
    }

    override fun onCreate(savedInstanceState: Bundle?) { // rendering the root of the application
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        perms += Manifest.permission.POST_NOTIFICATIONS
        requestPerms.launch(perms.toTypedArray())
        auth.addAuthStateListener { fa -> // reacting to login/logout
            val user = fa.currentUser // if the user is logged in: take the FCM token and write it in users/{uid}.fcmToken
            if (user != null) {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    db.collection("users").document(user.uid)
                        .update(mapOf("fcmToken" to token))
                }
                RankAutoUpdater.start() // start automatic rank updater
                maybeStartLocationService() // start foreground location service.
            } else { // If not
                RankAutoUpdater.stop() // turn off the rank updater and the location service.
                stopService(Intent(this, LocationUpdatesService::class.java))
            }
        }
    }

    private fun maybeStartLocationService() { // If there is a logged-in user, turn on LocationUpdatesService as foreground (notification is mandatory).
        val user = auth.currentUser ?: return
        val intent = Intent(this, LocationUpdatesService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun AppRoot() {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(0)
        showSplash = false
    }
    if (showSplash) {
        FullscreenSplash()
    } else {
        AuthHost()
    }
}

@Composable
fun FullscreenSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.splash_background)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo2),
            contentDescription = "ParkUp",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun AuthHost() {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    var screen by remember { mutableStateOf(if (auth.currentUser != null) "home" else "start") }
    var message by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var userData by remember { mutableStateOf<Map<String, Any>?>(null) }
    if (showDialog && message != null) {
        CustomPopup(message = message!!, onDismiss = { showDialog = false })
    }
    var userDocReg by remember { mutableStateOf<com.google.firebase.firestore.ListenerRegistration?>(null) }
    DisposableEffect(auth.currentUser?.uid) { //subscribe to users/{uid} while the user is logged in; on every change fill the local userData map so that HomeContent has fresh data.
                                            //DisposableEffect ensures that we remove the listener when the uid changes or the composable exits the composition.
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
                            "photoUrl" to (doc.getString("photoUrl") ?: ""),
                            "bio" to (doc.getString("bio") ?: ""),
                            "points" to (doc.getLong("points") ?: 0L),
                            "rank" to (doc.getLong("rank") ?: 0L)
                        )
                        println("userData updated: $userData")
                    }
                }
        }
        onDispose { userDocReg?.remove(); userDocReg = null }
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
                            screen = "home"; message = "Login successful"
                        } else {
                            message = "Login failed: ${task.exception?.message}"
                        }
                        showDialog = true
                    }
            },
            onForgotPassword = { email ->
                if (email.isNotBlank()) {
                    auth.sendPasswordResetEmail(email)
                        .addOnCompleteListener { task ->
                            message = if (task.isSuccessful) "Reset link sent to $email"
                            else "Failed to send reset link: ${task.exception?.message}"
                            showDialog = true
                        }
                } else { message = "Enter your email first!"; showDialog = true }
            },
            onNavigateToRegister = { screen = "register"; message = null; showDialog = false }
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
                                    "email" to email,
                                    "bio" to "",
                                    "points" to 0L,
                                    "rank" to 0L
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
            onBack = { screen = "login"; message = null; showDialog = false }
        )
        "home" -> HomeContent(
            userEmail = auth.currentUser?.email ?: "Unknown",
            userData = userData?.mapValues { it.value.toString() },
            onLogout = {
                RankAutoUpdater.stop()

                auth.signOut()
                screen = "start"
                userData = null
            }
        )
    }
}
