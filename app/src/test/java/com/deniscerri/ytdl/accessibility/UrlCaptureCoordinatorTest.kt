package com.deniscerri.ytdl.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlCaptureCoordinatorTest {
    @Test
    fun browserStrategyExtractsVisibleHttpUrlFromSupportedBrowserPackage() {
        val strategy = BrowserUrlCaptureStrategy()
        val snapshot = AccessibilityCaptureSnapshot(
            packageName = "com.android.chrome",
            visibleTexts = listOf("Address bar", "https://youtu.be/dQw4w9WgXcQ?si=test", "Share")
        )

        val candidate = strategy.findCandidate(snapshot)

        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=test", candidate?.url)
        assertEquals("com.android.chrome", candidate?.sourcePackage)
        assertEquals(CaptureSource.BROWSER_VISIBLE_TEXT, candidate?.source)
    }

    @Test
    fun coordinatorIgnoresUnsupportedForegroundPackages() {
        val coordinator = UrlCaptureCoordinator(
            strategies = listOf(BrowserUrlCaptureStrategy())
        )

        coordinator.updateSnapshot(
            AccessibilityCaptureSnapshot(
                packageName = "com.example.notes",
                visibleTexts = listOf("https://youtu.be/dQw4w9WgXcQ")
            )
        )

        assertNull(coordinator.currentCandidate)
        assertEquals(CaptureStatus.IDLE, coordinator.status)
    }

    @Test
    fun captureRequestReturnsCurrentCandidateWhenOneExists() {
        val coordinator = UrlCaptureCoordinator(
            strategies = listOf(BrowserUrlCaptureStrategy())
        )
        coordinator.updateSnapshot(
            AccessibilityCaptureSnapshot(
                packageName = "org.mozilla.firefox",
                visibleTexts = listOf("soundcloud.com", "https://soundcloud.com/artist/track")
            )
        )

        val result = coordinator.requestCapture()

        assertTrue(result is CaptureRequestResult.Captured)
        assertEquals("https://soundcloud.com/artist/track", (result as CaptureRequestResult.Captured).candidate.url)
        assertEquals(CaptureStatus.URL_FOUND, coordinator.status)
    }

    @Test
    fun captureRequestReportsFailureWhenNoCandidateExists() {
        val coordinator = UrlCaptureCoordinator(
            strategies = listOf(BrowserUrlCaptureStrategy())
        )
        coordinator.updateSnapshot(
            AccessibilityCaptureSnapshot(
                packageName = "com.android.chrome",
                visibleTexts = listOf("No URL here")
            )
        )

        val result = coordinator.requestCapture()

        assertTrue(result is CaptureRequestResult.Failed)
        assertEquals(CaptureStatus.FAILED, coordinator.status)
    }

    @Test
    fun captureRequestRejectsStaleCandidates() {
        var now = 1_000L
        val coordinator = UrlCaptureCoordinator(
            strategies = listOf(BrowserUrlCaptureStrategy()),
            clockMillis = { now },
            candidateMaxAgeMillis = 500L
        )
        coordinator.updateSnapshot(
            AccessibilityCaptureSnapshot(
                packageName = "com.android.chrome",
                visibleTexts = listOf("https://youtu.be/dQw4w9WgXcQ")
            )
        )
        now = 2_000L

        val result = coordinator.requestCapture()

        assertTrue(result is CaptureRequestResult.Failed)
        assertNull(coordinator.currentCandidate)
        assertEquals(CaptureStatus.FAILED, coordinator.status)
    }

    @Test
    fun captureRequestRejectsCandidatesFromDifferentForegroundPackage() {
        val coordinator = UrlCaptureCoordinator(
            strategies = listOf(BrowserUrlCaptureStrategy())
        )
        coordinator.updateSnapshot(
            AccessibilityCaptureSnapshot(
                packageName = "com.android.chrome",
                visibleTexts = listOf("https://youtu.be/dQw4w9WgXcQ")
            )
        )

        val result = coordinator.requestCapture(foregroundPackage = "org.mozilla.firefox")

        assertTrue(result is CaptureRequestResult.Failed)
        assertNull(coordinator.currentCandidate)
        assertEquals(CaptureStatus.FAILED, coordinator.status)
    }

    @Test
    fun browserStrategyPrioritizesAddressBarNodeOverVisiblePageLinks() {
        val strategy = BrowserUrlCaptureStrategy()
        val snapshot = AccessibilityCaptureSnapshot(
            packageName = "com.android.chrome",
            visibleTexts = listOf("https://ads.example/banner"),
            nodes = listOf(
                AccessibilityNodeSnapshot(
                    text = "https://youtube.com/watch?v=current",
                    viewIdResourceName = "com.android.chrome:id/url_bar"
                )
            )
        )

        val candidate = strategy.findCandidate(snapshot)

        assertEquals("https://youtube.com/watch?v=current", candidate?.url)
        assertEquals(Confidence.HIGH, candidate?.confidence)
    }

    @Test
    fun youtubeShareCopyStrategyPrefersCopyLinkOverShare() {
        val snapshot = AccessibilityCaptureSnapshot(
            packageName = "com.google.android.youtube",
            visibleTexts = emptyList(),
            nodes = listOf(
                AccessibilityNodeSnapshot(contentDescription = "Share"),
                AccessibilityNodeSnapshot(text = "Copy link")
            )
        )

        val target = YouTubeShareCopyLinkStrategy.chooseAction(snapshot)

        assertEquals(YouTubeShareCopyLinkAction.TAP_COPY_LINK, target?.action)
    }

    @Test
    fun youtubeShareCopyStrategyFindsShareByStableSelector() {
        val snapshot = AccessibilityCaptureSnapshot(
            packageName = "com.google.android.youtube",
            visibleTexts = emptyList(),
            nodes = listOf(
                AccessibilityNodeSnapshot(viewIdResourceName = "com.google.android.youtube:id/player_share_button")
            )
        )

        val target = YouTubeShareCopyLinkStrategy.chooseAction(snapshot)

        assertEquals(YouTubeShareCopyLinkAction.TAP_SHARE, target?.action)
    }

    @Test
    fun youtubeShareCopyStrategyExtractsOnlyYouTubeClipboardUrls() {
        val text = "Copied https://example.com/nope then https://youtu.be/dQw4w9WgXcQ?si=test"

        val url = YouTubeShareCopyLinkStrategy.extractYouTubeUrl(text)

        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=test", url)
    }

    @Test
    fun captureRequestStartsYoutubeAutomationWhenNoVisibleUrlExists() {
        val coordinator = UrlCaptureCoordinator(
            strategies = listOf(YouTubeUrlCaptureStrategy())
        )
        coordinator.updateSnapshot(
            AccessibilityCaptureSnapshot(
                packageName = "com.google.android.youtube",
                visibleTexts = listOf("Video title", "Share")
            )
        )

        val result = coordinator.requestCapture(foregroundPackage = "com.google.android.youtube")

        assertTrue(result is CaptureRequestResult.AutomationStarted)
        assertEquals(CaptureStatus.AUTOMATING, coordinator.status)
        assertEquals(YouTubeAutomationPhase.FIND_SHARE, coordinator.state.value.youtubeAutomationPhase)
        assertTrue(coordinator.shouldRunYouTubeAutomation("com.google.android.youtube"))
    }

    @Test
    fun youtubeAutomationWaitsForClipboardAfterCopyLinkClick() {
        val coordinator = UrlCaptureCoordinator(
            strategies = listOf(YouTubeUrlCaptureStrategy())
        )
        coordinator.updateSnapshot(
            AccessibilityCaptureSnapshot(
                packageName = "com.google.android.youtube",
                visibleTexts = listOf("Video title")
            )
        )
        coordinator.requestCapture(foregroundPackage = "com.google.android.youtube")

        coordinator.markYouTubeAutomationStep(
            YouTubeShareCopyLinkTarget(
                action = YouTubeShareCopyLinkAction.TAP_COPY_LINK,
                selector = "youtube-copy-link-label"
            )
        )

        assertTrue(coordinator.isWaitingForYouTubeClipboard())
    }
}
