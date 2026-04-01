package com.example.exp1

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class WajeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialise GlobalData first so SharedPreferences is ready
        // for both UI and BroadcastReceivers (TaskAlarmReceiver, etc.)
        GlobalData.init(this)

        val accountManager = AccountManager(this)
        val selectedLang = accountManager.getSelectedLanguage()

        val langTag = when (selectedLang) {
            "Tagalog" -> "tl"
            "Cebuano" -> "ceb"
            else -> "en"
        }

        val appLocales = LocaleListCompat.forLanguageTags(langTag)
        AppCompatDelegate.setApplicationLocales(appLocales)
    }
}