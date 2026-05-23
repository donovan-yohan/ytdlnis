package com.deniscerri.ytdl.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UrlCaptureAccessibilityService : AccessibilityService() {
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
    }

    override fun onInterrupt() {
        AccessibilityUrlCaptureCoordinator.markFailed("Accessibility URL capture was interrupted by Android.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityUrlCaptureCoordinator.clear("Accessibility URL capture service disconnected.")
        return super.onUnbind(intent)
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
                    viewIdResourceName = viewId
                )
            )
        }

        for (index in 0 until node.childCount) {
            collectNodeState(node.getChild(index), visibleTexts, viewIds, nodes, depth + 1)
            if (visibleTexts.size >= MAX_VISIBLE_TEXTS) break
        }
    }

    companion object {
        private const val MAX_TREE_DEPTH = 8
        private const val MAX_VISIBLE_TEXTS = 80
    }
}
