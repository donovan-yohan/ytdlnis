package com.deniscerri.ytdl.accessibility

class SoundCloudShareAutomationStrategy {
    fun supports(packageName: String): Boolean {
        return packageName in automationPackages
    }

    fun nextAction(snapshot: AccessibilityCaptureSnapshot): SoundCloudAutomationAction {
        if (!supports(snapshot.packageName)) {
            return SoundCloudAutomationAction.Ignore
        }

        val copyLinkNode = snapshot.nodes.firstOrNull { node ->
            node.isEnabled && node.matchesAny(COPY_LINK_HINTS)
        }
        if (copyLinkNode != null) {
            return SoundCloudAutomationAction.Click(SoundCloudAutomationTarget.COPY_LINK)
        }

        val shareNode = snapshot.nodes.firstOrNull { node ->
            snapshot.packageName in soundCloudPackages &&
                node.isEnabled &&
                node.matchesAny(SHARE_HINTS)
        }
        if (shareNode != null) {
            return SoundCloudAutomationAction.Click(SoundCloudAutomationTarget.SHARE)
        }

        return SoundCloudAutomationAction.Fallback(
            "SoundCloud link automation could not find Share or Copy Link. Open a track in SoundCloud, tap Share, then Copy Link or share the link to YTDLnis Quick Download."
        )
    }

    private fun AccessibilityNodeSnapshot.matchesAny(hints: List<String>): Boolean {
        return sequenceOf(text, contentDescription, viewIdResourceName)
            .filterNotNull()
            .map { it.lowercase() }
            .any { value -> hints.any(value::contains) }
    }

    companion object {
        val soundCloudPackages = setOf("com.soundcloud.android")
        val shareSheetPackages = setOf(
            "android",
            "com.android.intentresolver",
            "com.google.android.intentresolver"
        )
        val automationPackages: Set<String> = soundCloudPackages + shareSheetPackages

        private val SHARE_HINTS = listOf("share", "send")
        private val COPY_LINK_HINTS = listOf("copy link", "copy url", "copy soundcloud link")
    }
}

sealed class SoundCloudAutomationAction {
    data class Click(val target: SoundCloudAutomationTarget) : SoundCloudAutomationAction()
    data class Fallback(val message: String) : SoundCloudAutomationAction()
    data object Ignore : SoundCloudAutomationAction()
}

enum class SoundCloudAutomationTarget {
    SHARE,
    COPY_LINK
}
