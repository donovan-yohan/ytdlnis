package com.deniscerri.ytdl.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingBubblePositioningTest {
    @Test
    fun snapToEdge_clampsYAndChoosesLeftEdgeWhenBubbleCenterIsLeftOfScreenCenter() {
        val result = FloatingBubblePositioning.snapToEdge(
            current = BubblePosition(x = 120, y = -40),
            screen = BubbleScreenBounds(width = 1080, height = 1920),
            bubbleSizePx = 144,
            verticalMarginPx = 24
        )

        assertEquals(BubblePosition(x = 24, y = 24), result)
    }

    @Test
    fun snapToEdge_clampsYAndChoosesRightEdgeWhenBubbleCenterIsRightOfScreenCenter() {
        val result = FloatingBubblePositioning.snapToEdge(
            current = BubblePosition(x = 850, y = 2000),
            screen = BubbleScreenBounds(width = 1080, height = 1920),
            bubbleSizePx = 144,
            verticalMarginPx = 24
        )

        assertEquals(BubblePosition(x = 912, y = 1752), result)
    }

    @Test
    fun defaultPositionStartsOnRightEdgeAroundMiddleOfScreen() {
        val result = FloatingBubblePositioning.defaultPosition(
            screen = BubbleScreenBounds(width = 1080, height = 1920),
            bubbleSizePx = 144,
            edgeMarginPx = 24
        )

        assertEquals(BubblePosition(x = 912, y = 888), result)
    }
}
