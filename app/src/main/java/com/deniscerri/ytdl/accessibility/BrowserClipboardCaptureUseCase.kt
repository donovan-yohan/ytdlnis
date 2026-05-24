package com.deniscerri.ytdl.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.deniscerri.ytdl.core.download.QuickDownloadEnqueueUseCase
import com.deniscerri.ytdl.database.enums.DownloadType
import java.net.URI

class BrowserClipboardCaptureUseCase(
    private val coordinator: UrlCaptureCoordinator = AccessibilityUrlCaptureCoordinator,
    private val clipboardTextProvider: ClipboardTextProvider,
    private val quickDownloadEnqueueUseCase: QuickDownloadEnqueueUseCase,
    private val resolver: BrowserClipboardUrlResolver = BrowserClipboardUrlResolver(
        coordinator = coordinator,
        clipboardTextProvider = clipboardTextProvider
    )
) {
    suspend fun enqueueFromBubbleTap(
        defaultDownloadType: DownloadType? = null,
        requestedDownloadType: DownloadType? = null,
        foregroundPackage: String? = null
    ): BubbleCaptureEnqueueResult {
        return when (val resolution = resolver.resolve(foregroundPackage)) {
            is CaptureUrlResolution.Failed -> {
                coordinator.markFailed(resolution.reason)
                BubbleCaptureEnqueueResult.Failed(resolution.reason)
            }
            is CaptureUrlResolution.Resolved -> enqueueResolvedUrl(
                resolution = resolution,
                defaultDownloadType = defaultDownloadType,
                requestedDownloadType = requestedDownloadType
            )
        }
    }

    private suspend fun enqueueResolvedUrl(
        resolution: CaptureUrlResolution.Resolved,
        defaultDownloadType: DownloadType?,
        requestedDownloadType: DownloadType?
    ): BubbleCaptureEnqueueResult {
        coordinator.markQueueing()
        return runCatching {
            quickDownloadEnqueueUseCase.enqueueUrl(
                url = resolution.url,
                defaultDownloadType = defaultDownloadType,
                requestedDownloadType = requestedDownloadType
            )
        }.fold(
            onSuccess = { enqueueResult ->
                coordinator.markQueued()
                BubbleCaptureEnqueueResult.Queued(
                    url = resolution.url,
                    source = resolution.source,
                    enqueueResult = enqueueResult
                )
            },
            onFailure = { throwable ->
                val message = throwable.message?.takeIf(String::isNotBlank)
                    ?: "Failed to queue captured URL."
                coordinator.markFailed(message)
                BubbleCaptureEnqueueResult.Failed(message)
            }
        )
    }
}

class BrowserClipboardUrlResolver(
    private val coordinator: UrlCaptureCoordinator = AccessibilityUrlCaptureCoordinator,
    private val clipboardTextProvider: ClipboardTextProvider,
    private val validator: CaptureUrlValidator = CaptureUrlValidator()
) {
    fun resolve(foregroundPackage: String? = null): CaptureUrlResolution {
        val capturedUrl = when (val request = coordinator.requestCapture(foregroundPackage)) {
            is CaptureRequestResult.Captured -> request.candidate
            is CaptureRequestResult.Failed -> null
        }

        if (capturedUrl != null) {
            validator.normalizeSupportedUrl(capturedUrl.url)?.let { url ->
                return CaptureUrlResolution.Resolved(url, capturedUrl.source)
            }
        }

        val clipboardText = clipboardTextProvider.currentText()
        validator.firstSupportedUrl(clipboardText)?.let { url ->
            return CaptureUrlResolution.Resolved(url, CaptureSource.CLIPBOARD)
        }

        return if (clipboardText.isNullOrBlank()) {
            CaptureUrlResolution.Failed("No URL candidate found and clipboard is empty.")
        } else {
            CaptureUrlResolution.Failed("No supported URL found in the visible browser UI or clipboard.")
        }
    }
}

interface ClipboardTextProvider {
    fun currentText(): String?
}

class AndroidClipboardTextProvider(private val context: Context) : ClipboardTextProvider {
    override fun currentText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}

class CaptureUrlValidator(
    private val allowSafeGenericHttpUrls: Boolean = true
) {
    fun firstSupportedUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val candidates = mutableListOf<String>()
        val trimmedText = text.trim()
        candidates.add(trimmedText)
        candidates.addAll(UrlCandidateExtractor.extractUrls(trimmedText))
        schemeLessSupportedHostRegex.findAll(trimmedText).forEach { match ->
            candidates.add("https://${match.groupValues[1]}")
        }

        return candidates.asSequence()
            .mapNotNull(::normalizeSupportedUrl)
            .firstOrNull()
    }

    fun normalizeSupportedUrl(rawUrl: String): String? {
        val candidate = rawUrl.trim()
            .trimEnd('.', ',', ')', ']', '}')
            .takeIf(String::isNotBlank)
            ?: return null

        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null) return null

        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (host.isBlank()) return null

        if (hostMatches(host, knownSupportedHosts)) return candidate
        if (!allowSafeGenericHttpUrls) return null
        return candidate.takeIf { isSafeGenericHost(host) }
    }

    private fun isSafeGenericHost(host: String): Boolean {
        if (!host.contains('.')) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        val parsedOctets = host.split('.').map { it.toIntOrNull() }
        if (parsedOctets.size == 4 && parsedOctets.all { it != null && it in 0..255 }) {
            return isPublicIpv4(parsedOctets.map { it!! })
        }
        return true
    }

    private fun isPublicIpv4(octets: List<Int>): Boolean {
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 0 -> false
            first == 10 -> false
            first == 127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 168 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun hostMatches(host: String, allowedHosts: Set<String>): Boolean {
        return allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    companion object {
        private val knownSupportedHosts = setOf(
            "youtube.com",
            "youtu.be",
            "soundcloud.com",
            "on.soundcloud.com"
        )

        private val schemeLessSupportedHostRegex = Regex(
            """(?i)(?<![\w.-])((?:www\.)?(?:youtube\.com|youtu\.be|music\.youtube\.com|m\.youtube\.com|soundcloud\.com|on\.soundcloud\.com)/[^\s<>\"']+)"""
        )
    }
}

sealed class CaptureUrlResolution {
    data class Resolved(
        val url: String,
        val source: CaptureSource
    ) : CaptureUrlResolution()

    data class Failed(val reason: String) : CaptureUrlResolution()
}

sealed class BubbleCaptureEnqueueResult {
    data class Queued(
        val url: String,
        val source: CaptureSource,
        val enqueueResult: QuickDownloadEnqueueUseCase.EnqueueResult
    ) : BubbleCaptureEnqueueResult()

    data class Failed(val reason: String) : BubbleCaptureEnqueueResult()
}
