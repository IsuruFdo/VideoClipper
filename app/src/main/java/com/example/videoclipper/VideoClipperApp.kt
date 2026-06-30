package com.example.videoclipper

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class VideoClipperApp : Application() {

    companion object {
        const val PREFS_NAME = "video_clipper_prefs"
        const val KEY_THEME_MODE = "theme_mode"
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        applyThemeMode(prefs.getString(KEY_THEME_MODE, "system"))
    }
}

fun applyThemeMode(mode: String?) {
    AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )
}