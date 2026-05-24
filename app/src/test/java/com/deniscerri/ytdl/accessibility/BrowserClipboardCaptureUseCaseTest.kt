package com.deniscerri.ytdl.accessibility

import com.deniscerri.ytdl.core.download.QuickDownloadEnqueueUseCase
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.AudioPreferences
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.models.VideoPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserClipboardCaptureUseCaseTest {
    @Test
    fun resolverPrefersVisibleBrowserCandidateOverClipboardFallback() {
        val coordinator = UrlCaptureCoordinator(
            strategies = listOf(BrowserUrlCaptureStrategy())
        )
        coordinator.updateSnapshot(
            AccessibilityCaptureSnapshot(
                packageName = "com.android.chrome",
                visibleTexts = listOf("https://youtu.be/current")
            )
        )
        val resolver = BrowserClipboardUrlResolver(
            coordinator = coordinator,
            clipboardTextProvider = FakeClipboardTextProvider("https://soundcloud.com/from/clipboard")
        )

        val result = resolver.resolve()

        assertTrue(result is CaptureUrlResolution.Resolved)
        val resolved = result as CaptureUrlResolution.Resolved
        assertEquals("https://youtu.be/current", resolved.url)
        assertEquals(CaptureSource.BROWSER_VISIBLE_TEXT, resolved.source)
    }

    @Test
    fun resolverFallsBackToClipboardWhenAccessibleCandidateIsMissing() {
        val coordinator = UrlCaptureCoordinator(
            strategies = listOf(BrowserUrlCaptureStrategy())
        )
        val resolver = BrowserClipboardUrlResolver(
            coordinator = coordinator,
            clipboardTextProvider = FakeClipboardTextProvider("watch later: soundcloud.com/artist/track")
        )

        val result = resolver.resolve()

        assertTrue(result is CaptureUrlResolution.Resolved)
        val resolved = result as CaptureUrlResolution.Resolved
        assertEquals("https://soundcloud.com/artist/track", resolved.url)
        assertEquals(CaptureSource.CLIPBOARD, resolved.source)
    }

    @Test
    fun resolverRejectsEmptyClipboardWithVisibleFailureReason() {
        val resolver = BrowserClipboardUrlResolver(
            coordinator = UrlCaptureCoordinator(strategies = listOf(BrowserUrlCaptureStrategy())),
            clipboardTextProvider = FakeClipboardTextProvider("   ")
        )

        val result = resolver.resolve()

        assertTrue(result is CaptureUrlResolution.Failed)
        assertEquals("No URL candidate found and clipboard is empty.", (result as CaptureUrlResolution.Failed).reason)
    }

    @Test
    fun validatorRejectsUnsafeGenericLocalhostUrl() {
        val validator = CaptureUrlValidator()

        assertNull(validator.firstSupportedUrl("http://localhost:8080/video"))
        assertNull(validator.firstSupportedUrl("https://192.168.1.10/video"))
    }

    @Test
    fun enqueueFromBubbleTapQueuesResolvedUrlThroughSharedUseCaseAndMarksQueued() = runBlocking {
        val url = "https://youtu.be/fromClipboard"
        val coordinator = UrlCaptureCoordinator(strategies = listOf(BrowserUrlCaptureStrategy()))
        val quickDownload = QuickDownloadEnqueueUseCase(
            resultStore = FakeResultStore(emptyResult = resultItem(url)),
            downloadGateway = FakeDownloadGateway(downloadItem(url, DownloadType.audio))
        )
        val useCase = BrowserClipboardCaptureUseCase(
            coordinator = coordinator,
            clipboardTextProvider = FakeClipboardTextProvider(url),
            quickDownloadEnqueueUseCase = quickDownload
        )

        val result = useCase.enqueueFromBubbleTap(defaultDownloadType = DownloadType.audio)

        assertTrue(result is BubbleCaptureEnqueueResult.Queued)
        val queued = result as BubbleCaptureEnqueueResult.Queued
        assertEquals(url, queued.url)
        assertEquals(CaptureSource.CLIPBOARD, queued.source)
        assertEquals(CaptureStatus.QUEUED, coordinator.status)
    }

    @Test
    fun enqueueFromBubbleTapSurfacesInvalidClipboardFailureWithoutQueueing() = runBlocking {
        val coordinator = UrlCaptureCoordinator(strategies = listOf(BrowserUrlCaptureStrategy()))
        val downloads = FakeDownloadGateway(downloadItem("https://example.com/video", DownloadType.video))
        val quickDownload = QuickDownloadEnqueueUseCase(
            resultStore = FakeResultStore(),
            downloadGateway = downloads
        )
        val useCase = BrowserClipboardCaptureUseCase(
            coordinator = coordinator,
            clipboardTextProvider = FakeClipboardTextProvider("not a url"),
            quickDownloadEnqueueUseCase = quickDownload
        )

        val result = useCase.enqueueFromBubbleTap()

        assertTrue(result is BubbleCaptureEnqueueResult.Failed)
        assertEquals("No supported URL found in the visible browser UI or clipboard.", (result as BubbleCaptureEnqueueResult.Failed).reason)
        assertEquals(CaptureStatus.FAILED, coordinator.status)
        assertTrue(downloads.queuedItems.isEmpty())
    }

    private class FakeClipboardTextProvider(private val text: String?) : ClipboardTextProvider {
        override fun currentText(): String? = text
    }

    private class FakeResultStore(
        private val resultsByUrl: Map<String, List<ResultItem>> = emptyMap(),
        private val emptyResult: ResultItem? = null
    ) : QuickDownloadEnqueueUseCase.ResultStore {
        override suspend fun getAllByURL(url: String): List<ResultItem> = resultsByUrl[url].orEmpty()

        override suspend fun deleteAll() = Unit

        override fun createEmptyResultItem(url: String): ResultItem = emptyResult ?: resultItem(url)
    }

    private class FakeDownloadGateway(
        private val itemToCreate: DownloadItem,
        private val resolvedType: DownloadType = itemToCreate.type
    ) : QuickDownloadEnqueueUseCase.DownloadGateway {
        var queuedItems: List<DownloadItem> = emptyList()

        override fun resolveDownloadType(downloadType: DownloadType?, url: String): DownloadType = resolvedType

        override fun createDownloadItemFromResult(result: ResultItem, downloadType: DownloadType): DownloadItem = itemToCreate

        override suspend fun queueDownloads(items: List<DownloadItem>): QuickDownloadEnqueueUseCase.QueueResult {
            queuedItems = items
            return QuickDownloadEnqueueUseCase.QueueResult(message = "queued")
        }
    }

    companion object {
        private fun resultItem(url: String, id: Long = 0) = ResultItem(
            id,
            url,
            "",
            "",
            "",
            "",
            "",
            "",
            arrayListOf(),
            "",
            arrayListOf(),
            "",
            null,
            0
        )

        private fun downloadItem(url: String, type: DownloadType) = DownloadItem(
            0,
            url,
            "",
            "",
            "",
            "",
            type,
            Format(),
            "",
            "",
            mutableListOf(),
            "",
            "",
            "",
            "",
            AudioPreferences(),
            VideoPreferences(),
            "",
            "",
            false,
            "Queued",
            0,
            null
        )
    }
}
