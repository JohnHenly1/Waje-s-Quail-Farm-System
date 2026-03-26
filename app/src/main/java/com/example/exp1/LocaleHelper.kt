package com.example.exp1

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    fun setLocale(context: Context, language: String): Context {
        val locale = when (language) {
            "Tagalog" -> Locale("tl")
            "Cebuano" -> Locale("ceb")
            else -> Locale("en")
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
