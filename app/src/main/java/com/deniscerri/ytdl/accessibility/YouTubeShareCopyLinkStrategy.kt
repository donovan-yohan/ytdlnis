package com.deniscerri.ytdl.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

enum class YouTubeShareCopyLinkAction {
    TAP_SHARE,
    TAP_COPY_LINK
}

data class YouTubeShareCopyLinkTarget(
    val action: YouTubeShareCopyLinkAction,
    val selector: String
)

/**
 * YouTube's UI changes often, so keep all Share -> Copy link selectors in one small file.
 * Prefer stable resource-id hints and accessibility labels. Coordinate taps are intentionally
 * not used here; callers should surface the manual fallback instead of guessing screen geometry.
 */
object YouTubeShareCopyLinkStrategy {
    private val shareSelectors = listOf(
        NodeSelector(
            name = "youtube-share-view-id",
            viewIdHints = listOf("share_button", "player_share_button", "menu_item_share")
        ),
        NodeSelector(
            name = "youtube-share-label",
            exactLabels = listOf("share"),
            labelContains = listOf("share video", "share this video")
        )
    )

    private val copyLinkSelectors = listOf(
        NodeSelector(
            name = "youtube-copy-link-view-id",
            viewIdHints = listOf("copy_link", "copylink", "copy_url", "share_copy")
        ),
        NodeSelector(
            name = "youtube-copy-link-label",
            exactLabels = listOf("copy link"),
            labelContains = listOf("copy link", "copy url", "copy video link")
        )
    )

    fun chooseAction(snapshot: AccessibilityCaptureSnapshot): YouTubeShareCopyLinkTarget? {
        if (!UrlCaptureStrategies.youtubePackages.contains(snapshot.packageName)) return null

        findSnapshotMatch(snapshot.nodes, copyLinkSelectors)?.let { selector ->
            return YouTubeShareCopyLinkTarget(YouTubeShareCopyLinkAction.TAP_COPY_LINK, selector)
        }

        findSnapshotMatch(snapshot.nodes, shareSelectors)?.let { selector ->
            return YouTubeShareCopyLinkTarget(YouTubeShareCopyLinkAction.TAP_SHARE, selector)
        }

        return null
    }

    fun performNextAction(root: AccessibilityNodeInfo?): YouTubeAutomationStepResult {
        if (root == null) return YouTubeAutomationStepResult.NoTarget

        findNode(root, copyLinkSelectors)?.let { (node, selectorName) ->
            return if (performClick(node)) {
                YouTubeAutomationStepResult.Clicked(
                    YouTubeShareCopyLinkTarget(YouTubeShareCopyLinkAction.TAP_COPY_LINK, selectorName)
                )
            } else {
                YouTubeAutomationStepResult.ClickFailed(
                    YouTubeShareCopyLinkTarget(YouTubeShareCopyLinkAction.TAP_COPY_LINK, selectorName)
                )
            }
        }

        findNode(root, shareSelectors)?.let { (node, selectorName) ->
            return if (performClick(node)) {
                YouTubeAutomationStepResult.Clicked(
                    YouTubeShareCopyLinkTarget(YouTubeShareCopyLinkAction.TAP_SHARE, selectorName)
                )
            } else {
                YouTubeAutomationStepResult.ClickFailed(
                    YouTubeShareCopyLinkTarget(YouTubeShareCopyLinkAction.TAP_SHARE, selectorName)
                )
            }
        }

        return YouTubeAutomationStepResult.NoTarget
    }

    fun extractYouTubeUrl(text: String): String? {
        return UrlCandidateExtractor.extractUrls(text)
            .firstOrNull { url -> UrlCandidateExtractor.hostMatches(url, YOUTUBE_URL_HOSTS) }
    }

    private fun findSnapshotMatch(
        nodes: List<AccessibilityNodeSnapshot>,
        selectors: List<NodeSelector>
    ): String? {
        return selectors.firstNotNullOfOrNull { selector ->
            nodes.firstOrNull { node -> selector.matches(node) }?.let { selector.name }
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        selectors: List<NodeSelector>
    ): Pair<AccessibilityNodeInfo, String>? {
        selectors.forEach { selector ->
            findNodeMatching(root, selector)?.let { node -> return node to selector.name }
        }
        return null
    }

    private fun findNodeMatching(
        node: AccessibilityNodeInfo,
        selector: NodeSelector,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH) return null
        if (selector.matches(node)) return node

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNodeMatching(child, selector, depth + 1)?.let { return it }
        }

        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLICKABLE_PARENT_DEPTH + 1) {
            val candidate = current ?: return@repeat
            if (candidate.isEnabled && candidate.isClickable) {
                return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = candidate.parent
        }

        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private data class NodeSelector(
        val name: String,
        val viewIdHints: List<String> = emptyList(),
        val exactLabels: List<String> = emptyList(),
        val labelContains: List<String> = emptyList()
    ) {
        fun matches(node: AccessibilityNodeSnapshot): Boolean {
            return matches(
                text = node.text,
                contentDescription = node.contentDescription,
                viewIdResourceName = node.viewIdResourceName
            )
        }

        fun matches(node: AccessibilityNodeInfo): Boolean {
            return matches(
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                viewIdResourceName = node.viewIdResourceName
            )
        }

        private fun matches(
            text: String?,
            contentDescription: String?,
            viewIdResourceName: String?
        ): Boolean {
            val normalizedViewId = viewIdResourceName.normalized()
            if (normalizedViewId != null && viewIdHints.any { normalizedViewId.contains(it) }) {
                return true
            }

            val labels = listOfNotNull(text.normalized(), contentDescription.normalized())
            if (labels.any { label -> exactLabels.any { label == it } }) {
                return true
            }

            return labels.any { label -> labelContains.any { hint -> label.contains(hint) } }
        }
    }

    private fun String?.normalized(): String? {
        return this
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
    }

    private val YOUTUBE_URL_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be"
    )

    private const val MAX_TREE_DEPTH = 12
    private const val MAX_CLICKABLE_PARENT_DEPTH = 4
}

sealed class YouTubeAutomationStepResult {
    data class Clicked(val target: YouTubeShareCopyLinkTarget) : YouTubeAutomationStepResult()
    data class ClickFailed(val target: YouTubeShareCopyLinkTarget) : YouTubeAutomationStepResult()
    data object NoTarget : YouTubeAutomationStepResult()
}
