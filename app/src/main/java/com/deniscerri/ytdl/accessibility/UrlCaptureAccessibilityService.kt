package com.deniscerri.ytdl.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.deniscerri.ytdl.receiver.ShareActivity

class UrlCaptureAccessibilityService : AccessibilityService() {
    private val soundCloudAutomationStrategy = SoundCloudShareAutomationStrategy()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var waitingForSoundCloudClipboard = false
    private var soundCloudFallbackScheduled = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString() ?: return
        val captureSupported = UrlCaptureStrategies.supportedPackages.contains(packageName)
        val soundCloudAutomationActive = AccessibilityUrlCaptureCoordinator.isSoundCloudAutomationActive(packageName) &&
            soundCloudAutomationStrategy.supports(packageName)
        if (!captureSupported && !soundCloudAutomationActive) return

        val visibleTexts = linkedSetOf<String>()
        val viewIds = linkedSetOf<String>()
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        event.text?.mapNotNullTo(visibleTexts) { it?.toString()?.takeIf(String::isNotBlank) }
        collectNodeState(rootInActiveWindow, visibleTexts, viewIds, nodes)

        val snapshot = AccessibilityCaptureSnapshot(
            packageName = packageName,
            visibleTexts = visibleTexts.toList(),
            viewIds = viewIds.toList(),
            className = event.className?.toString(),
            nodes = nodes
        )

        if (captureSupported) {
            AccessibilityUrlCaptureCoordinator.updateSnapshot(snapshot)
        }

        if (soundCloudAutomationActive) {
            attemptSoundCloudAutomation(snapshot)
        }
    }

    override fun onInterrupt() {
        waitingForSoundCloudClipboard = false
        soundCloudFallbackScheduled = false
        AccessibilityUrlCaptureCoordinator.markFailed("Accessibility URL capture was interrupted by Android.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        waitingForSoundCloudClipboard = false
        soundCloudFallbackScheduled = false
        AccessibilityUrlCaptureCoordinator.clear("Accessibility URL capture service disconnected.")
        return super.onUnbind(intent)
    }

    private fun attemptSoundCloudAutomation(snapshot: AccessibilityCaptureSnapshot) {
        if (waitingForSoundCloudClipboard) {
            readSoundCloudUrlFromClipboard()?.let { copiedUrl ->
                enqueueCopiedSoundCloudUrl(copiedUrl)
            }
            return
        }

        when (val action = soundCloudAutomationStrategy.nextAction(snapshot)) {
            is SoundCloudAutomationAction.Click -> {
                soundCloudFallbackScheduled = false
                val clicked = performClickForTarget(action.target)
                if (!clicked) {
                    showSoundCloudFallback("SoundCloud link automation found ${action.target.label} but Android would not click it.")
                    return
                }

                AccessibilityUrlCaptureCoordinator.markSoundCloudAutomationStep(
                    when (action.target) {
                        SoundCloudAutomationTarget.SHARE -> "Opened SoundCloud share controls"
                        SoundCloudAutomationTarget.COPY_LINK -> "Tapped SoundCloud Copy Link"
                    }
                )

                if (action.target == SoundCloudAutomationTarget.COPY_LINK) {
                    waitingForSoundCloudClipboard = true
                    soundCloudFallbackScheduled = false
                    mainHandler.postDelayed({
                        if (waitingForSoundCloudClipboard) {
                            readSoundCloudUrlFromClipboard()?.let(::enqueueCopiedSoundCloudUrl)
                                ?: showSoundCloudFallback("SoundCloud Copy Link did not place a SoundCloud URL on the clipboard.")
                        }
                    }, CLIPBOARD_CHECK_DELAY_MILLIS)
                }
            }
            is SoundCloudAutomationAction.Fallback -> scheduleSoundCloudFallback(action.message)
            SoundCloudAutomationAction.Ignore -> Unit
        }
    }

    private fun performClickForTarget(target: SoundCloudAutomationTarget): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findActionNode(root, target) ?: return false
        return clickNodeOrClickableAncestor(node)
    }

    private fun findActionNode(
        node: AccessibilityNodeInfo?,
        target: SoundCloudAutomationTarget,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH) return null
        if (node.isEnabled && node.matchesTarget(target)) return node

        for (index in 0 until node.childCount) {
            val childMatch = findActionNode(node.getChild(index), target, depth + 1)
            if (childMatch != null) return childMatch
        }
        return null
    }

    private fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun AccessibilityNodeInfo.matchesTarget(target: SoundCloudAutomationTarget): Boolean {
        val hints = when (target) {
            SoundCloudAutomationTarget.SHARE -> SHARE_HINTS
            SoundCloudAutomationTarget.COPY_LINK -> COPY_LINK_HINTS
        }
        return sequenceOf(text, contentDescription, viewIdResourceName)
            .filterNotNull()
            .map { it.toString().lowercase() }
            .any { value -> hints.any(value::contains) }
    }

    private fun readSoundCloudUrlFromClipboard(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        for (index in 0 until clip.itemCount) {
            val text = clip.getItemAt(index).coerceToText(this)?.toString().orEmpty()
            UrlCandidateExtractor.extractUrls(text)
                .firstOrNull { url -> UrlCandidateExtractor.hostMatches(url, SOUNDCLOUD_HOSTS) }
                ?.let { return it }
        }
        return null
    }

    private fun enqueueCopiedSoundCloudUrl(url: String) {
        waitingForSoundCloudClipboard = false
        soundCloudFallbackScheduled = false
        AccessibilityUrlCaptureCoordinator.markSoundCloudAutomationQueued(url)
        val shareIntent = Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra("quick_download", true)
            putExtra("BACKGROUND", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(shareIntent)
        Toast.makeText(this, "Queued copied SoundCloud link", Toast.LENGTH_SHORT).show()
    }

    private fun showSoundCloudFallback(message: String) {
        waitingForSoundCloudClipboard = false
        soundCloudFallbackScheduled = false
        AccessibilityUrlCaptureCoordinator.markSoundCloudAutomationFailed(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun scheduleSoundCloudFallback(message: String) {
        if (soundCloudFallbackScheduled) return
        soundCloudFallbackScheduled = true
        AccessibilityUrlCaptureCoordinator.markSoundCloudAutomationStep("Waiting for SoundCloud Share or Copy Link controls")
        mainHandler.postDelayed({
            if (soundCloudFallbackScheduled && !waitingForSoundCloudClipboard) {
                showSoundCloudFallback(message)
            }
        }, FALLBACK_GRACE_DELAY_MILLIS)
    }

    private fun collectNodeState(
        node: AccessibilityNodeInfo?,
        visibleTexts: MutableSet<String>,
        viewIds: MutableSet<String>,
        nodes: MutableList<AccessibilityNodeSnapshot>,
        depth: Int = 0
    ) {
        if (node == null || depth > MAX_TREE_DEPTH || visibleTexts.size >= MAX_VISIBLE_TEXTS) return

        val text = node.text?.toString()?.takeIf(String::isNotBlank)
        val contentDescription = node.contentDescription?.toString()?.takeIf(String::isNotBlank)
        val viewId = node.viewIdResourceName?.takeIf(String::isNotBlank)

        text?.let(visibleTexts::add)
        contentDescription?.let(visibleTexts::add)
        viewId?.let(viewIds::add)
        if (text != null || contentDescription != null || viewId != null) {
            nodes.add(
                AccessibilityNodeSnapshot(
                    text = text,
                    contentDescription = contentDescription,
                    viewIdResourceName = viewId,
                    isClickable = node.isClickable,
                    isEnabled = node.isEnabled
                )
            )
        }

        for (index in 0 until node.childCount) {
            collectNodeState(node.getChild(index), visibleTexts, viewIds, nodes, depth + 1)
            if (visibleTexts.size >= MAX_VISIBLE_TEXTS) break
        }
    }

    private val SoundCloudAutomationTarget.label: String
        get() = when (this) {
            SoundCloudAutomationTarget.SHARE -> "Share"
            SoundCloudAutomationTarget.COPY_LINK -> "Copy Link"
        }

    companion object {
        private const val MAX_TREE_DEPTH = 8
        private const val MAX_VISIBLE_TEXTS = 80
        private const val CLIPBOARD_CHECK_DELAY_MILLIS = 700L
        private const val FALLBACK_GRACE_DELAY_MILLIS = 1_500L
        private val SOUNDCLOUD_HOSTS = setOf("soundcloud.com", "www.soundcloud.com", "on.soundcloud.com")
        private val SHARE_HINTS = listOf("share", "send")
        private val COPY_LINK_HINTS = listOf("copy link", "copy url", "copy soundcloud link")
    }
}
