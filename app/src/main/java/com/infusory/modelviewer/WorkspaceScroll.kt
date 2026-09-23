package com.infusory.modelviewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Gesture handling for the empty workspace background - anything not
 * covered by a model container. Applied to a full-size `Box` that sits
 * below every `ModelContainer` in z-order, so a touch inside any container
 * never reaches it. The only thing a background touch does is scroll the
 * workspace vertically with a one-finger drag.
 */
internal fun Modifier.workspaceScrollGestures(
    scrollOffsetPx: () -> Float,
    maxScrollPx: () -> Float,
    onScrollChange: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val changes = event.changes
            if (changes.size == 1) {
                val change = changes[0]
                val dy = change.position.y - change.previousPosition.y
                val updated = (scrollOffsetPx() - dy).coerceIn(0f, maxScrollPx())
                change.consume()
                onScrollChange(updated)
            }
        } while (event.changes.any { it.pressed })
    }
}
