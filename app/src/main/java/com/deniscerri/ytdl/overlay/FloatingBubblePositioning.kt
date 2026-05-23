package com.deniscerri.ytdl.overlay

import kotlin.math.max
import kotlin.math.min

/** Pure positioning rules for the floating capture bubble. */
data class BubblePosition(val x: Int, val y: Int)

data class BubbleScreenBounds(val width: Int, val height: Int)

object FloatingBubblePositioning {
    fun defaultPosition(
        screen: BubbleScreenBounds,
        bubbleSizePx: Int,
        edgeMarginPx: Int
    ): BubblePosition {
        val x = (screen.width - bubbleSizePx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        val y = ((screen.height - bubbleSizePx) / 2).coerceAtLeast(edgeMarginPx)
        return BubblePosition(x, y)
    }

    fun snapToEdge(
        current: BubblePosition,
        screen: BubbleScreenBounds,
        bubbleSizePx: Int,
        verticalMarginPx: Int
    ): BubblePosition {
        val bubbleCenterX = current.x + bubbleSizePx / 2
        val snappedX = if (bubbleCenterX < screen.width / 2) {
            verticalMarginPx
        } else {
            screen.width - bubbleSizePx - verticalMarginPx
        }.coerceAtLeast(verticalMarginPx)

        val minY = verticalMarginPx
        val maxY = max(minY, screen.height - bubbleSizePx - verticalMarginPx)
        return BubblePosition(
            x = snappedX,
            y = min(max(current.y, minY), maxY)
        )
    }
}
