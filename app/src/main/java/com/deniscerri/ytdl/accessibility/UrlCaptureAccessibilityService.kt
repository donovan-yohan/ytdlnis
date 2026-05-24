package com.deniscerri.ytdl.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.deniscerri.ytdl.receiver.ShareActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UrlCaptureAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var clipboardManager: ClipboardManager
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChanged()
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        observeAutomationRequests()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString() ?: return
        if (!UrlCaptureStrategies.supportedPackages.contains(packageName)) return

        val visibleTexts = linkedSetOf<String>()
        val viewIds = linkedSetOf<String>()
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        event.text?.mapNotNullTo(visibleTexts) { it?.toString()?.takeIf(String::isNotBlank) }
        collectNodeState(rootInActiveWindow, visibleTexts, viewIds, nodes)

        AccessibilityUrlCaptureCoordinator.updateSnapshot(
            AccessibilityCaptureSnapshot(
                packageName = packageName,
                visibleTexts = visibleTexts.toList(),
                viewIds = viewIds.toList(),
                className = event.className?.toString(),
                nodes = nodes
            )
        )

        maybeRunYouTubeAutomation(packageName, rootInActiveWindow)
    }

    override fun onDestroy() {
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipboardListener) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        AccessibilityUrlCaptureCoordinator.markFailed("Accessibility URL capture was interrupted by Android.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityUrlCaptureCoordinator.clear("Accessibility URL capture service disconnected.")
        return super.onUnbind(intent)
    }

    private fun observeAutomationRequests() {
        serviceScope.launch {
            AccessibilityUrlCaptureCoordinator.state.collectLatest { state ->
                if (state.status == CaptureStatus.AUTOMATING &&
                    state.youtubeAutomationPhase == YouTubeAutomationPhase.FIND_SHARE
                ) {
                    state.foregroundPackage?.let { packageName ->
                        maybeRunYouTubeAutomation(packageName, rootInActiveWindow)
                    }
                }
            }
        }
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

    private fun maybeRunYouTubeAutomation(packageName: String, root: AccessibilityNodeInfo?) {
        if (!AccessibilityUrlCaptureCoordinator.shouldRunYouTubeAutomation(packageName)) return
        if (root?.packageName?.toString() != packageName) {
            if (AccessibilityUrlCaptureCoordinator.youtubeAutomationTimedOut()) {
                failYouTubeAutomation("YouTube is no longer the active window. Open the video and try capture again.")
            }
            return
        }

        when (val result = YouTubeShareCopyLinkStrategy.performNextAction(root)) {
            is YouTubeAutomationStepResult.Clicked -> {
                AccessibilityUrlCaptureCoordinator.markYouTubeAutomationStep(result.target)
            }

            is YouTubeAutomationStepResult.ClickFailed -> {
                failYouTubeAutomation("Could not tap YouTube ${result.target.action.humanName()}. Copy the link manually or use YouTube's native Share to YTDLnis.")
            }

            YouTubeAutomationStepResult.NoTarget -> {
                val phase = AccessibilityUrlCaptureCoordinator.state.value.youtubeAutomationPhase
                if (phase == YouTubeAutomationPhase.FIND_SHARE) {
                    failYouTubeAutomation("Could not find YouTube Share control. Copy the link manually or use YouTube's native Share to YTDLnis.")
                } else if (AccessibilityUrlCaptureCoordinator.youtubeAutomationTimedOut()) {
                    failYouTubeAutomation("Could not find YouTube Share > Copy link controls. Copy the link manually or use YouTube's native Share to YTDLnis.")
                }
            }
        }
    }

    private fun handleClipboardChanged() {
        if (!::clipboardManager.isInitialized) return
        if (!AccessibilityUrlCaptureCoordinator.isWaitingForYouTubeClipboard()) return

        val clip = clipboardManager.primaryClip ?: return
        val url = clip.firstYouTubeUrl() ?: return
        enqueueCopiedUrl(url)
        AccessibilityUrlCaptureCoordinator.markQueued()
    }

    private fun ClipData.firstYouTubeUrl(): String? {
        for (index in 0 until itemCount) {
            val text = getItemAt(index)?.coerceToText(this@UrlCaptureAccessibilityService)?.toString() ?: continue
            YouTubeShareCopyLinkStrategy.extractYouTubeUrl(text)?.let { return it }
        }
        return null
    }

    private fun enqueueCopiedUrl(url: String) {
        AccessibilityUrlCaptureCoordinator.markQueueing()
        val intent = Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra("quick_download", true)
            putExtra("BACKGROUND", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun failYouTubeAutomation(message: String) {
        AccessibilityUrlCaptureCoordinator.markFailed(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun YouTubeShareCopyLinkAction.humanName(): String {
        return when (this) {
            YouTubeShareCopyLinkAction.TAP_SHARE -> "Share"
            YouTubeShareCopyLinkAction.TAP_COPY_LINK -> "Copy link"
        }
    }

    companion object {
        private const val MAX_TREE_DEPTH = 8
        private const val MAX_VISIBLE_TEXTS = 80
    }
}
