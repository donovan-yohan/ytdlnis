package com.deniscerri.ytdl.overlay

import com.deniscerri.ytdl.accessibility.CaptureStatus
import com.deniscerri.ytdl.database.enums.DownloadType
import java.net.URI

enum class FloatingBubbleQuickAction {
    DOWNLOAD_AUDIO,
    DOWNLOAD_VIDEO,
    PASTE_URL,
    OPEN_QUEUE,
    STOP_BUBBLE
}

data class FloatingBubbleVisualState(
    val label: String,
    val statusLabel: String
)

object FloatingBubbleStateRenderer {
    fun render(status: CaptureStatus): FloatingBubbleVisualState {
        return when (status) {
            CaptureStatus.IDLE -> FloatingBubbleVisualState("↓", "Idle")
            CaptureStatus.URL_FOUND -> FloatingBubbleVisualState("URL", "URL found")
            CaptureStatus.QUEUEING -> FloatingBubbleVisualState("…", "Queueing")
            CaptureStatus.QUEUED -> FloatingBubbleVisualState("✓", "Queued")
            CaptureStatus.FAILED -> FloatingBubbleVisualState("!", "Failed")
        }
    }
}

object FloatingBubbleQuickActions {
    const val YOUTUBE_DEFAULT_TYPE_PREFERENCE_KEY = "floating_capture_bubble_youtube_default_type"
    const val SOUNDCLOUD_DEFAULT_TYPE_PREFERENCE_KEY = "floating_capture_bubble_soundcloud_default_type"

    val panelActions = listOf(
        FloatingBubbleQuickAction.DOWNLOAD_AUDIO,
        FloatingBubbleQuickAction.DOWNLOAD_VIDEO,
        FloatingBubbleQuickAction.PASTE_URL,
        FloatingBubbleQuickAction.OPEN_QUEUE,
        FloatingBubbleQuickAction.STOP_BUBBLE
    )

    fun defaultDownloadTypeForUrl(
        url: String,
        readPreference: (String) -> String?
    ): DownloadType? {
        val host = runCatching { URI(url).host?.lowercase()?.trimEnd('.') }.getOrNull()
            ?: return null
        val preferenceKey = when {
            isYoutubeHost(host) -> YOUTUBE_DEFAULT_TYPE_PREFERENCE_KEY
            isSoundCloudHost(host) -> SOUNDCLOUD_DEFAULT_TYPE_PREFERENCE_KEY
            else -> return null
        }
        return when (readPreference(preferenceKey)) {
            DownloadType.audio.toString() -> DownloadType.audio
            DownloadType.video.toString() -> DownloadType.video
            else -> null
        }
    }

    private fun isYoutubeHost(host: String): Boolean {
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun isSoundCloudHost(host: String): Boolean {
        return host == "soundcloud.com" || host.endsWith(".soundcloud.com")
    }
}
