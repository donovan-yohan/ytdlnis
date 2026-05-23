package com.deniscerri.ytdl.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

object AccessibilityUrlCaptureSettings {
    const val PREFERENCE_KEY = "accessibility_url_capture"

    fun settingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, UrlCaptureAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        return splitter.any { service -> service.equals(expected, ignoreCase = true) }
    }
}
