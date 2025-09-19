package com.stosic.parkup

import android.app.Application
import com.google.firebase.FirebaseApp

class ParkUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
