package com.example.exp1

import android.app.Application
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale

class WajeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialise GlobalData first so SharedPreferences is ready
        // for both UI and BroadcastReceivers (TaskAlarmReceiver, etc.)
        GlobalData.init(this)

        // ── FIX: Ensure Firebase Auth always has a valid session ────────────
        // The app uses a custom role/password system stored in Firestore, but
        // Firebase Security Rules require request.auth != null.  Signing in
        // anonymously gives every app install a stable Firebase UID without
        // changing the existing custom login flow.
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnFailureListener { e ->
                    // Non-fatal: log the error but don't crash.
                    // The app will still work offline; Firestore/Storage
                    // operations that require auth will surface their own errors.
                    android.util.Log.e("WajeApplication", "Anonymous sign-in failed: ${e.message}")
                }
        }
        // ───────────────────────────────────────────────────────────────────

        val accountManager = AccountManager(this)
        val selectedLang = accountManager.getSelectedLanguage()

        val langTag = when (selectedLang) {
            "Tagalog" -> "fil"
            "Cebuano" -> "ceb"
            else -> "en"
        }

        // Apply immediately to the current process's resources
        val locale = Locale.forLanguageTag(langTag)
        Locale.setDefault(locale)

        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        // This is deprecated but still useful for immediate effect in some OS versions
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)

        // Set for AppCompat (persistence and modern way)
        val appLocales = LocaleListCompat.forLanguageTags(langTag)
        AppCompatDelegate.setApplicationLocales(appLocales)
    }
}
