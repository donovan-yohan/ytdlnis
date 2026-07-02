package com.deniscerri.ytdl.overlay

import com.deniscerri.ytdl.accessibility.CaptureStatus
import com.deniscerri.ytdl.database.enums.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingBubbleQuickActionsTest {
    @Test
    fun `panel exposes compact quick actions in required order`() {
        assertEquals(
            listOf(
                FloatingBubbleQuickAction.DOWNLOAD_AUDIO,
                FloatingBubbleQuickAction.DOWNLOAD_VIDEO,
                FloatingBubbleQuickAction.PASTE_URL,
                FloatingBubbleQuickAction.OPEN_QUEUE,
                FloatingBubbleQuickAction.STOP_BUBBLE
            ),
            FloatingBubbleQuickActions.panelActions
        )
    }

    @Test
    fun `status rendering gives every capture state a distinct visible label`() {
        val labels = CaptureStatus.entries.map { FloatingBubbleStateRenderer.render(it).label }

        assertEquals(listOf("↓", "URL", "YT", "SC", "…", "✓", "!"), labels)
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `youtube urls use youtube bubble default download type`() {
        val selected = FloatingBubbleQuickActions.defaultDownloadTypeForUrl(
            url = "https://music.youtube.com/watch?v=abc123",
            readPreference = { key ->
                if (key == FloatingBubbleQuickActions.YOUTUBE_DEFAULT_TYPE_PREFERENCE_KEY) "audio" else null
            }
        )

        assertEquals(DownloadType.audio, selected)
    }

    @Test
    fun `soundcloud urls use soundcloud bubble default download type`() {
        val selected = FloatingBubbleQuickActions.defaultDownloadTypeForUrl(
            url = "https://on.soundcloud.com/abc123",
            readPreference = { key ->
                if (key == FloatingBubbleQuickActions.SOUNDCLOUD_DEFAULT_TYPE_PREFERENCE_KEY) "video" else null
            }
        )

        assertEquals(DownloadType.video, selected)
    }

    @Test
    fun `app default or unknown urls do not override download type`() {
        assertNull(
            FloatingBubbleQuickActions.defaultDownloadTypeForUrl(
                url = "https://example.com/video",
                readPreference = { "audio" }
            )
        )
        assertNull(
            FloatingBubbleQuickActions.defaultDownloadTypeForUrl(
                url = "https://youtube.com/watch?v=abc123",
                readPreference = { "" }
            )
        )
        assertNull(
            FloatingBubbleQuickActions.defaultDownloadTypeForUrl(
                url = "not a url",
                readPreference = { "audio" }
            )
        )
    }
}
