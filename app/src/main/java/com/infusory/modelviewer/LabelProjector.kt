package com.infusory.modelviewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.android.filament.View
import dev.romainguy.kotlin.math.dot
import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode
import io.github.sceneview.utils.worldToScreen
import kotlin.math.sqrt

/**
 * Projects one model's labelled nodes through the camera into screen space
 * and lays out their callouts. GLB metadata parsing lives in
 * [GlbLabelParser] and runs once at load time; this runs per frame, only
 * while labels are visible, and only recomputes when the model's
 * transform actually changed (see `ModelInstance.needsLabelRecompute`).
 */
object LabelProjector {

    private data class Anchored(val label: NodeLabel, val anchor: Offset)

    fun project(
        instance: ModelInstance,
        cameraNode: CameraNode,
        view: View,
        content: Rect
    ): List<ScreenLabel> {
        val visibilityRect = content
        val cameraPosition = cameraNode.worldPosition
        val cameraForward = cameraNode.forwardDirection

        // Project every labelled node's world position through the camera,
        // dropping any that are behind the camera or outside the viewport.
        val anchored = instance.labels.mapNotNull { label ->
            val worldPos = instance.node.getWorldPosition(
                Position(label.localTranslation[0], label.localTranslation[1], label.localTranslation[2])
            )
            if (dot(worldPos - cameraPosition, cameraForward) <= 0f) return@mapNotNull null

            val screen = view.worldToScreen(worldPos)
            val anchor = Offset(screen.x, screen.y)
            if (!visibilityRect.contains(anchor)) return@mapNotNull null
            Anchored(label, anchor)
        }

        // Initial callout placement: push each label radially outward from
        // the container's center, in the same direction as its anchor, so
        // labels fan out around the model instead of stacking in one spot.
        val contentCx = content.left + content.width / 2f
        val contentCy = content.top + content.height / 2f
        val gap = 24f
        val labels = anchored.map { (label, anchor) ->
            val textWidth = label.text.length * 15f + 28f
            val textHeight = 40f

            val dx = anchor.x - contentCx
            val dy = anchor.y - contentCy
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val dirX = if (dist > 1f) dx / dist else 0.7f
            val dirY = if (dist > 1f) dy / dist else 0.7f

            val pushX = gap + textWidth / 2f
            val pushY = gap + textHeight / 2f
            val centerX = anchor.x + dirX * pushX
            val centerY = anchor.y + dirY * pushY
            val textPos = Offset(centerX - textWidth / 2f, centerY - textHeight / 2f)

            ScreenLabel(
                anchor = anchor,
                textPos = textPos,
                text = label.text,
                bounds = Rect(textPos, Size(textWidth, textHeight))
            )
        }.toMutableList()

        // Collision avoidance: nudge overlapping labels apart along
        // whichever axis has the smaller overlap, then clamp everyone into
        // the content rect so labels never cover the header.
        repeat(12) {
            for (i in labels.indices) {
                var current = labels[i]
                for (j in labels.indices) {
                    if (i == j) continue
                    val other = labels[j]
                    if (current.bounds.overlaps(other.bounds)) {
                        val overlapX = minOf(current.bounds.right, other.bounds.right) -
                                maxOf(current.bounds.left, other.bounds.left)
                        val overlapY = minOf(current.bounds.bottom, other.bounds.bottom) -
                                maxOf(current.bounds.top, other.bounds.top)
                        current = if (overlapX < overlapY) {
                            val pushX = (overlapX / 2f + 3f).let {
                                if (current.bounds.left <= other.bounds.left) -it else it
                            }
                            current.copy(
                                textPos = current.textPos + Offset(pushX, 0f),
                                bounds = current.bounds.translate(pushX, 0f)
                            )
                        } else {
                            val pushY = (overlapY / 2f + 3f).let {
                                if (current.bounds.top <= other.bounds.top) -it else it
                            }
                            current.copy(
                                textPos = current.textPos + Offset(0f, pushY),
                                bounds = current.bounds.translate(0f, pushY)
                            )
                        }
                    }
                }
                labels[i] = current
            }
        }

        for (i in labels.indices) {
            val current = labels[i]
            val minX = content.left + 6f
            val maxX = (content.right - current.bounds.width - 6f).coerceAtLeast(minX)
            val minY = content.top + 6f
            val maxY = (content.bottom - current.bounds.height - 6f).coerceAtLeast(minY)
            val clampedX = current.textPos.x.coerceIn(minX, maxX)
            val clampedY = current.textPos.y.coerceIn(minY, maxY)
            val diff = Offset(clampedX - current.textPos.x, clampedY - current.textPos.y)
            labels[i] = current.copy(
                textPos = Offset(clampedX, clampedY),
                bounds = current.bounds.translate(diff)
            )
        }

        return labels
    }

    /** Nearest point on [rect]'s boundary (or interior) to [point], so a
     *  connector line touches the edge of its callout box, not its center. */
    fun closestPointOnRect(rect: Rect, point: Offset): Offset {
        val x = point.x.coerceIn(rect.left, rect.right)
        val y = point.y.coerceIn(rect.top, rect.bottom)
        return Offset(x, y)
    }
}

internal fun recomputeLabelsIfNeeded(instance: ModelInstance, cameraNode: CameraNode, view: View, headerHeightPx: Float) {
    if (!instance.labelsVisible || instance.labels.isEmpty()) return
    if (!instance.needsLabelRecompute() && instance.screenLabels.isNotEmpty()) return
    instance.screenLabels = LabelProjector.project(
        instance = instance,
        cameraNode = cameraNode,
        view = view,
        content = instance.localContentRect(headerHeightPx)
    )
    instance.updateLastState()
}
