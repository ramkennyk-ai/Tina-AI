package com.tinaai.app

import android.app.Application
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/**
 * Signs in anonymously on launch, same as Talksy — RTDB security rules
 * require auth != null (see README-captions.md / README.md for the rules).
 * Firebase itself initializes automatically from google-services.json via
 * the Gradle plugin — no manual FirebaseApp.initializeApp() call needed.
 */
class TinaApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        FirebaseDatabase.getInstance().setPersistenceEnabled(false) // signaling data is transient, no need to cache

        if (FirebaseAuth.getInstance().currentUser == null) {
            FirebaseAuth.getInstance().signInAnonymously()
        }
    }
}
