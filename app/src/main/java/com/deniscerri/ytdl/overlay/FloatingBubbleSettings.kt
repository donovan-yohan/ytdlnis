package com.deniscerri.ytdl.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

object FloatingBubbleSettings {
    const val ENABLED_PREFERENCE_KEY = "floating_capture_bubble_enabled"
    const val POSITION_X_PREFERENCE_KEY = "floating_capture_bubble_x"
    const val POSITION_Y_PREFERENCE_KEY = "floating_capture_bubble_y"

    fun overlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(ENABLED_PREFERENCE_KEY, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(ENABLED_PREFERENCE_KEY, enabled)
            .apply()
    }

    fun startService(context: Context) {
        val intent = Intent(context, FloatingBubbleService::class.java)
            .setAction(FloatingBubbleService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService(context: Context) {
        context.startService(
            Intent(context, FloatingBubbleService::class.java)
                .setAction(FloatingBubbleService.ACTION_STOP)
        )
    }
}
