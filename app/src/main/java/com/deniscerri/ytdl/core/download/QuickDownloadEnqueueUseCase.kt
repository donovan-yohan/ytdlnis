package com.deniscerri.ytdl.core.download

import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.ResultItem

class QuickDownloadEnqueueUseCase(
    private val resultStore: ResultStore,
    private val downloadGateway: DownloadGateway
) {
    interface ResultStore {
        suspend fun getAllByURL(url: String): List<ResultItem>
        suspend fun deleteAll()
        fun createEmptyResultItem(url: String): ResultItem
    }

    interface DownloadGateway {
        fun resolveDownloadType(downloadType: DownloadType?, url: String): DownloadType
        fun createDownloadItemFromResult(result: ResultItem, downloadType: DownloadType): DownloadItem
        suspend fun queueDownloads(items: List<DownloadItem>): QueueResult
    }

    data class QueueResult(
        val message: String = "",
        val duplicateDownloadIDs: List<DuplicateDownloadIDs> = emptyList()
    )

    data class DuplicateDownloadIDs(
        val downloadItemID: Long,
        val historyItemID: Long?
    )

    data class EnqueueResult(
        val resultItem: ResultItem,
        val downloadType: DownloadType,
        val downloadItem: DownloadItem,
        val queueResult: QueueResult
    )

    data class PreparedDownload(
        val resultItem: ResultItem,
        val downloadType: DownloadType
    )

    suspend fun prepareDownload(
        url: String,
        defaultDownloadType: DownloadType? = null,
        requestedDownloadType: DownloadType? = null
    ): PreparedDownload {
        val result = resolveResult(url)
        val downloadType = downloadGateway.resolveDownloadType(
            requestedDownloadType ?: defaultDownloadType,
            result.url
        )
        return PreparedDownload(result, downloadType)
    }

    suspend fun enqueueUrl(
        url: String,
        defaultDownloadType: DownloadType? = null,
        requestedDownloadType: DownloadType? = null
    ): EnqueueResult {
        return enqueuePrepared(prepareDownload(url, defaultDownloadType, requestedDownloadType))
    }

    suspend fun enqueuePrepared(preparedDownload: PreparedDownload): EnqueueResult {
        val downloadItem = downloadGateway.createDownloadItemFromResult(
            preparedDownload.resultItem,
            preparedDownload.downloadType
        )
        val queueResult = downloadGateway.queueDownloads(listOf(downloadItem))

        return EnqueueResult(
            resultItem = preparedDownload.resultItem,
            downloadType = preparedDownload.downloadType,
            downloadItem = downloadItem,
            queueResult = queueResult
        )
    }

    private suspend fun resolveResult(url: String): ResultItem {
        val existingResults = resultStore.getAllByURL(url)
        return if (existingResults.size == 1) {
            existingResults.first()
        } else {
            resultStore.deleteAll()
            resultStore.createEmptyResultItem(url)
        }
    }
}
