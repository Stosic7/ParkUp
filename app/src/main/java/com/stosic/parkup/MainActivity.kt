package com.stosic.parkup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stosic.parkup.auth.data.AuthRepository
import com.stosic.parkup.auth.ui.RegisterScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RegisterScreenHost()
        }
    }
}

@Composable
fun RegisterScreenHost() {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    RegisterScreen(
        onPickPhoto = { /* TODO: dodaj upload kasnije */ },
        onRegisterClick = { email, pass, ime, prezime, telefon ->
            scope.launch {
                val result = AuthRepository.registerUser(email, pass, ime, prezime, telefon)
                message = result.fold(
                    onSuccess = { "Uspešna registracija: ${it.email}" },
                    onFailure = { "Greška: ${it.message}" }
                )
            }
        },
        onBack = {}
    )

    message?.let {
        Text(
            text = it,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = if (it.startsWith("Greška")) Color.Red else Color.Green
        )
    }
}
