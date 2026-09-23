package com.infusory.modelviewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.min

/**
 * Gesture handling for one model's container. Normal Mode: 1 finger moves
 * the container, 2 fingers resize it. Interaction Mode: 1 finger rotates
 * the model, 2 fingers zoom it. Compose only delivers events here when a
 * touch actually starts inside this container's box, so the workspace
 * background never triggers these.
 */
internal fun Modifier.modelContainerGestures(
    instance: ModelInstance,
    minContainerPx: Float,
    maxContainerPx: Float,
    /** Largest (width, height) this container could grow to while staying
     *  centered on the given point and inside the visible workspace - read
     *  live on every call, not captured once. */
    maxSizeFor: (Offset) -> Offset,
    clampOffset: (Offset) -> Offset,
    onSelect: () -> Unit,
    onTransformChanged: () -> Unit,
): Modifier = pointerInput(instance.id, instance.interactionMode) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onSelect()
        do {
            val event = awaitPointerEvent()
            val changes = event.changes
            when {
                changes.size == 1 -> {
                    val change = changes[0]
                    val drag = change.position - change.previousPosition
                    if (!instance.interactionMode) {
                        instance.containerOffset = clampOffset(instance.containerOffset + drag)
                    } else {
                        instance.rotY += drag.x * ROTATE_SENSITIVITY
                        instance.rotX += drag.y * ROTATE_SENSITIVITY
                    }
                    change.consume()
                    onTransformChanged()
                }
                changes.size >= 2 -> {
                    val zoomChange = event.calculateZoom()
                    if (!instance.interactionMode) {
                        // Resize grows/shrinks around the container's own
                        // center, so both edges move - not just the
                        // bottom-right corner.
                        val center = Offset(
                            instance.containerOffset.x + instance.containerSize.x / 2f,
                            instance.containerOffset.y + instance.containerSize.y / 2f
                        )
                        val maxAllowed = maxSizeFor(center)
                        val ceilingX = min(maxContainerPx, maxAllowed.x).coerceAtLeast(minContainerPx)
                        val ceilingY = min(maxContainerPx, maxAllowed.y).coerceAtLeast(minContainerPx)
                        val newX = (instance.containerSize.x * zoomChange)
                            .coerceIn(minContainerPx, ceilingX)
                        val newY = (instance.containerSize.y * zoomChange)
                            .coerceIn(minContainerPx, ceilingY)
                        instance.containerSize = Offset(newX, newY)
                        instance.containerOffset = Offset(center.x - newX / 2f, center.y - newY / 2f)
                    } else {
                        instance.zoom = (instance.zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    }
                    changes.forEach { it.consume() }
                    onTransformChanged()
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
