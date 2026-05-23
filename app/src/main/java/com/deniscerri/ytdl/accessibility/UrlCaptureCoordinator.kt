package com.deniscerri.ytdl.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small, UI-agnostic seam between AccessibilityService collection and future overlay / enqueue UI.
 * The service feeds snapshots in; callers can read status/currentCandidate and request a capture.
 */
object AccessibilityUrlCaptureCoordinator : UrlCaptureCoordinator()

open class UrlCaptureCoordinator(
    private val strategies: List<UrlCaptureStrategy> = UrlCaptureStrategies.defaultStrategies,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val candidateMaxAgeMillis: Long = DEFAULT_CANDIDATE_MAX_AGE_MILLIS
) {
    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    val status: CaptureStatus
        get() = _state.value.status

    val currentCandidate: CaptureUrlCandidate?
        get() = _state.value.currentCandidate

    fun updateSnapshot(snapshot: AccessibilityCaptureSnapshot) {
        val strategy = strategies.firstOrNull { it.supports(snapshot.packageName) }
        if (strategy == null) {
            _state.value = CaptureState(
                status = CaptureStatus.IDLE,
                foregroundPackage = snapshot.packageName,
                currentCandidate = null,
                message = "Unsupported foreground app"
            )
            return
        }

        val candidate = strategy.findCandidate(snapshot)
        _state.value = CaptureState(
            status = if (candidate == null) CaptureStatus.IDLE else CaptureStatus.URL_FOUND,
            foregroundPackage = snapshot.packageName,
            currentCandidate = candidate,
            candidateCapturedAtMillis = candidate?.let { clockMillis() },
            message = if (candidate == null) "No URL candidate visible" else "URL candidate found"
        )
    }

    fun requestCapture(foregroundPackage: String? = null): CaptureRequestResult {
        val snapshot = _state.value
        val candidate = snapshot.currentCandidate
        if (candidate == null) {
            return failAndClear("No URL candidate found. Open a supported app or browser page with a visible URL.")
        }

        if (foregroundPackage != null && foregroundPackage != candidate.sourcePackage) {
            return failAndClear("URL candidate is no longer from the foreground app.")
        }

        val capturedAt = snapshot.candidateCapturedAtMillis
        if (capturedAt == null || clockMillis() - capturedAt > candidateMaxAgeMillis) {
            return failAndClear("URL candidate expired. Capture again from the supported foreground app.")
        }

        _state.value = snapshot.copy(
            status = CaptureStatus.URL_FOUND,
            message = "Ready to queue ${candidate.url}"
        )
        return CaptureRequestResult.Captured(candidate)
    }

    fun clear(message: String? = null) {
        _state.value = CaptureState(message = message)
    }

    fun markQueueing() {
        _state.value = _state.value.copy(status = CaptureStatus.QUEUEING, message = "Queueing captured URL")
    }

    fun markQueued() {
        _state.value = _state.value.copy(status = CaptureStatus.QUEUED, message = "Captured URL queued")
    }

    fun markFailed(message: String) {
        _state.value = _state.value.copy(status = CaptureStatus.FAILED, message = message)
    }

    private fun failAndClear(message: String): CaptureRequestResult.Failed {
        _state.value = CaptureState(status = CaptureStatus.FAILED, message = message)
        return CaptureRequestResult.Failed(message)
    }

    companion object {
        const val DEFAULT_CANDIDATE_MAX_AGE_MILLIS = 15_000L
    }
}

data class CaptureState(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val foregroundPackage: String? = null,
    val currentCandidate: CaptureUrlCandidate? = null,
    val candidateCapturedAtMillis: Long? = null,
    val message: String? = null
)

enum class CaptureStatus {
    IDLE,
    URL_FOUND,
    QUEUEING,
    QUEUED,
    FAILED
}

sealed class CaptureRequestResult {
    data class Captured(val candidate: CaptureUrlCandidate) : CaptureRequestResult()
    data class Failed(val reason: String) : CaptureRequestResult()
}

data class CaptureUrlCandidate(
    val url: String,
    val sourcePackage: String,
    val source: CaptureSource,
    val confidence: Confidence = Confidence.MEDIUM
)

enum class CaptureSource {
    BROWSER_VISIBLE_TEXT,
    YOUTUBE_VISIBLE_TEXT,
    SOUNDCLOUD_VISIBLE_TEXT,
    GENERIC_VISIBLE_TEXT,
    CLIPBOARD
}

enum class Confidence {
    LOW,
    MEDIUM,
    HIGH
}

data class AccessibilityCaptureSnapshot(
    val packageName: String,
    val visibleTexts: List<String>,
    val viewIds: List<String> = emptyList(),
    val className: String? = null,
    val nodes: List<AccessibilityNodeSnapshot> = emptyList()
)

data class AccessibilityNodeSnapshot(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null
)

interface UrlCaptureStrategy {
    val supportedPackages: Set<String>

    fun supports(packageName: String): Boolean = supportedPackages.contains(packageName)

    fun findCandidate(snapshot: AccessibilityCaptureSnapshot): CaptureUrlCandidate?
}

object UrlCaptureStrategies {
    val browserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.brave.browser",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.duckduckgo.mobile.android"
    )

    val youtubePackages = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music"
    )

    val soundCloudPackages = setOf("com.soundcloud.android")

    val supportedPackages: Set<String> = browserPackages + youtubePackages + soundCloudPackages

    val defaultStrategies: List<UrlCaptureStrategy> = listOf(
        BrowserUrlCaptureStrategy(),
        YouTubeUrlCaptureStrategy(),
        SoundCloudUrlCaptureStrategy()
    )
}

class BrowserUrlCaptureStrategy : TextScanningUrlCaptureStrategy(
    packages = UrlCaptureStrategies.browserPackages,
    source = CaptureSource.BROWSER_VISIBLE_TEXT,
    confidence = Confidence.MEDIUM
) {
    override fun findCandidate(snapshot: AccessibilityCaptureSnapshot): CaptureUrlCandidate? {
        val addressBarUrl = snapshot.nodes
            .asSequence()
            .filter { node -> node.viewIdResourceName?.let(::isAddressBarViewId) == true }
            .flatMap { node -> sequenceOf(node.text, node.contentDescription).filterNotNull() }
            .flatMap { text -> UrlCandidateExtractor.extractUrls(text).asSequence() }
            .firstOrNull()

        if (addressBarUrl != null) {
            return CaptureUrlCandidate(
                url = addressBarUrl,
                sourcePackage = snapshot.packageName,
                source = CaptureSource.BROWSER_VISIBLE_TEXT,
                confidence = Confidence.HIGH
            )
        }

        return super.findCandidate(snapshot)
    }

    private fun isAddressBarViewId(viewId: String): Boolean {
        val lower = viewId.lowercase()
        return ADDRESS_BAR_VIEW_ID_HINTS.any(lower::contains)
    }

    companion object {
        private val ADDRESS_BAR_VIEW_ID_HINTS = listOf(
            "url_bar",
            "location_bar",
            "address_bar",
            "search_box_text",
            "mozac_browser_toolbar_url_view",
            "browser_toolbar_url"
        )
    }
}

class YouTubeUrlCaptureStrategy : TextScanningUrlCaptureStrategy(
    packages = UrlCaptureStrategies.youtubePackages,
    source = CaptureSource.YOUTUBE_VISIBLE_TEXT,
    confidence = Confidence.LOW,
    allowedHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com")
)

class SoundCloudUrlCaptureStrategy : TextScanningUrlCaptureStrategy(
    packages = UrlCaptureStrategies.soundCloudPackages,
    source = CaptureSource.SOUNDCLOUD_VISIBLE_TEXT,
    confidence = Confidence.LOW,
    allowedHosts = setOf("soundcloud.com", "www.soundcloud.com", "on.soundcloud.com")
)

open class TextScanningUrlCaptureStrategy(
    private val packages: Set<String>,
    private val source: CaptureSource,
    private val confidence: Confidence,
    private val allowedHosts: Set<String>? = null
) : UrlCaptureStrategy {
    override val supportedPackages: Set<String> = packages

    override fun findCandidate(snapshot: AccessibilityCaptureSnapshot): CaptureUrlCandidate? {
        return snapshot.visibleTexts
            .asSequence()
            .flatMap { UrlCandidateExtractor.extractUrls(it).asSequence() }
            .firstOrNull { url -> allowedHosts == null || UrlCandidateExtractor.hostMatches(url, allowedHosts) }
            ?.let { url ->
                CaptureUrlCandidate(
                    url = url,
                    sourcePackage = snapshot.packageName,
                    source = source,
                    confidence = confidence
                )
            }
    }
}

object UrlCandidateExtractor {
    private val urlRegex = Regex("""https?://[^\s<>\"']+""")

    fun extractUrls(text: String): List<String> {
        return urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '}') }
            .filter { isUsableUrl(it) }
            .distinct()
            .toList()
    }

    fun hostMatches(url: String, allowedHosts: Set<String>): Boolean {
        val host = parseHost(url) ?: return false
        return allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    private fun isUsableUrl(url: String): Boolean {
        val host = parseHost(url) ?: return false
        return host.contains('.')
    }

    private fun parseHost(url: String): String? {
        val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isBlank()) return null
        return withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .lowercase()
            .takeIf { it.isNotBlank() }
    }
}
