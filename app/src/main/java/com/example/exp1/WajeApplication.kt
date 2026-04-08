package com.example.exp1

import android.app.Application
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

class WajeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialise GlobalData first so SharedPreferences is ready
        // for both UI and BroadcastReceivers (TaskAlarmReceiver, etc.)
        GlobalData.init(this)

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