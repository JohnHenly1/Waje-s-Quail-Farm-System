package com.example.exp1

import android.app.Application
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.initialize
import java.util.Locale

class WajeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // ── App Check: must run before any other Firebase call ──────────────
        Firebase.initialize(this)
        val providerFactory = if (BuildConfig.DEBUG) {
            DebugAppCheckProviderFactory.getInstance()
        } else {
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }
        Firebase.appCheck.installAppCheckProviderFactory(providerFactory)
        // ──────────────────────────────────────────────────────────────────

        // Initialise GlobalData first so SharedPreferences is ready
        // for both UI and BroadcastReceivers (TaskAlarmReceiver, etc.)
        GlobalData.init(this)

        // ── FIX: Ensure Firebase Auth always has a valid session ────────────
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnFailureListener { e ->
                    android.util.Log.e("WajeApplication", "Anonymous sign-in failed: ${e.message}")
                }
        }
        // ───────────────────────────────────────────────────────────────────

        MaintenanceGuard.start(this)
        AlertsMonitor.start(this)

        // Make sure this device's FCM topic subscriptions match its current
        // Notification Preferences toggles (covers fresh installs and the
        // case where prefs were changed on another device for a shared login).
        PushTopics.syncSubscriptions(this)

        val accountManager = AccountManager(this)
        val selectedLang = accountManager.getSelectedLanguage()

        val langTag = when (selectedLang) {
            "Tagalog" -> "fil"
            else -> "en"
        }

        val locale = Locale.forLanguageTag(langTag)
        Locale.setDefault(locale)

        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)

        val appLocales = LocaleListCompat.forLanguageTags(langTag)
        AppCompatDelegate.setApplicationLocales(appLocales)
    }
}