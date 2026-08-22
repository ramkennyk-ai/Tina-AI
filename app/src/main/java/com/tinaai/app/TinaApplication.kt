package com.tinaai.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/**
 * TINA application entry point.
 *
 * Firebase is initialized when Firebase configuration is available.
 * During development, the app can also start without Firebase so the
 * UI and other features can be tested independently.
 */
class TinaApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        initializeFirebase()
    }

    private fun initializeFirebase() {
        try {
            val firebaseApp = FirebaseApp.initializeApp(this)

            // No google-services.json / Firebase configuration available.
            if (firebaseApp == null) {
                return
            }

            FirebaseDatabase.getInstance()
                .setPersistenceEnabled(false)

            val auth = FirebaseAuth.getInstance()

            if (auth.currentUser == null) {
                auth.signInAnonymously()
                    .addOnFailureListener { error ->
                        // Firebase is optional during development.
                        // Do not crash the application if anonymous auth fails.
                        error.printStackTrace()
                    }
            }

        } catch (e: Exception) {
            // Firebase is optional for the initial UI/debug build.
            // Prevent Firebase initialization problems from crashing TINA.
            e.printStackTrace()
        }
    }
}