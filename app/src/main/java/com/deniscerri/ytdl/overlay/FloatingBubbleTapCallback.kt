package com.deniscerri.ytdl.overlay

import android.content.Context
import android.content.Intent
import com.deniscerri.ytdl.receiver.ShareActivity

interface FloatingBubbleTapCallback {
    fun onCapturedUrl(context: Context, url: String)
}

object ShareActivityFloatingBubbleTapCallback : FloatingBubbleTapCallback {
    override fun onCapturedUrl(context: Context, url: String) {
        val shareIntent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra("BACKGROUND", true)
            putExtra("quick_download", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(shareIntent)
    }
}
