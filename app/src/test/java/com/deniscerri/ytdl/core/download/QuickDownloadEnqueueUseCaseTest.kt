package com.deniscerri.ytdl.core.download

import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.AudioPreferences
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.models.VideoPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickDownloadEnqueueUseCaseTest {
    @Test
    fun enqueueUrl_usesSingleExistingResultWithoutClearingResults() = runBlocking {
        val url = "https://example.com/watch?v=1"
        val existingResult = resultItem(url)
        val queuedItem = downloadItem(url, DownloadType.audio)
        val results = FakeResultStore(mapOf(url to listOf(existingResult)))
        val downloads = FakeDownloadGateway(queuedItem)
        val useCase = QuickDownloadEnqueueUseCase(results, downloads)

        val output = useCase.enqueueUrl(url, defaultDownloadType = DownloadType.audio)

        assertFalse(results.deletedAll)
        assertSame(existingResult, downloads.createdFromResult)
        assertEquals(DownloadType.audio, downloads.resolveRequestedType)
        assertEquals(url, downloads.resolveUrl)
        assertEquals(DownloadType.audio, downloads.createdWithType)
        assertEquals(listOf(queuedItem), downloads.queuedItems)
        assertSame(queuedItem, output.downloadItem)
    }

    @Test
    fun enqueueUrl_createsEmptyResultAfterClearingWhenExistingResultsAreMissingOrAmbiguous() = runBlocking {
        val url = "https://example.com/watch?v=2"
        val emptyResult = resultItem(url)
        val queuedItem = downloadItem(url, DownloadType.video)
        val results = FakeResultStore(
            resultsByUrl = mapOf(url to listOf(resultItem(url, id = 1), resultItem(url, id = 2))),
            emptyResult = emptyResult
        )
        val downloads = FakeDownloadGateway(queuedItem)
        val useCase = QuickDownloadEnqueueUseCase(results, downloads)

        useCase.enqueueUrl(url, defaultDownloadType = DownloadType.video)

        assertTrue(results.deletedAll)
        assertEquals(url, results.emptyResultUrl)
        assertSame(emptyResult, downloads.createdFromResult)
        assertEquals(listOf(queuedItem), downloads.queuedItems)
    }

    @Test
    fun enqueueUrl_prefersExplicitTypeOverBackgroundDefaultType() = runBlocking {
        val url = "https://soundcloud.com/example/track"
        val result = resultItem(url)
        val queuedItem = downloadItem(url, DownloadType.video)
        val results = FakeResultStore(mapOf(url to listOf(result)))
        val downloads = FakeDownloadGateway(queuedItem, resolvedType = DownloadType.video)
        val useCase = QuickDownloadEnqueueUseCase(results, downloads)

        useCase.enqueueUrl(
            url = url,
            defaultDownloadType = DownloadType.audio,
            requestedDownloadType = DownloadType.video
        )

        assertEquals(DownloadType.video, downloads.resolveRequestedType)
        assertEquals(DownloadType.video, downloads.createdWithType)
    }

    private class FakeResultStore(
        private val resultsByUrl: Map<String, List<ResultItem>> = emptyMap(),
        private val emptyResult: ResultItem? = null
    ) : QuickDownloadEnqueueUseCase.ResultStore {
        var deletedAll = false
        var emptyResultUrl: String? = null

        override suspend fun getAllByURL(url: String): List<ResultItem> = resultsByUrl[url].orEmpty()

        override suspend fun deleteAll() {
            deletedAll = true
        }

        override fun createEmptyResultItem(url: String): ResultItem {
            emptyResultUrl = url
            return emptyResult ?: resultItem(url)
        }
    }

    private class FakeDownloadGateway(
        private val itemToCreate: DownloadItem,
        private val resolvedType: DownloadType = itemToCreate.type
    ) : QuickDownloadEnqueueUseCase.DownloadGateway {
        var resolveRequestedType: DownloadType? = null
        var resolveUrl: String? = null
        var createdFromResult: ResultItem? = null
        var createdWithType: DownloadType? = null
        var queuedItems: List<DownloadItem> = emptyList()

        override fun resolveDownloadType(downloadType: DownloadType?, url: String): DownloadType {
            resolveRequestedType = downloadType
            resolveUrl = url
            return resolvedType
        }

        override fun createDownloadItemFromResult(result: ResultItem, downloadType: DownloadType): DownloadItem {
            createdFromResult = result
            createdWithType = downloadType
            return itemToCreate
        }

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
