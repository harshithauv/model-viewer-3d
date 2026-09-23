package com.infusory.modelviewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlin.math.min
import kotlin.math.sqrt

internal val HEADER_HEIGHT_DP = 36.dp

internal val BASE_CONTAINER_SIZE_DP = 220.dp
internal val MIN_CONTAINER_DP = 175.dp
internal val MAX_CONTAINER_DP = 480.dp

internal const val MIN_ZOOM = 0.3f

// Each container renders through its own bounded Filament View/Scene/Camera
// (see Model3DViewport), so a heavily zoomed-in model is genuinely clipped
// at the container's edge - a generous zoom range is safe here.
internal const val MAX_ZOOM = 4f
internal const val ROTATE_SENSITIVITY = 0.35f // degrees per dragged pixel

// A pure 1.0 fit would touch the container's edges exactly.
internal const val FIT_PADDING = 1.1f

// Every newly-added model lands in its own full-width row below the
// previous one, so models never start out overlapping.
internal val ROW_HEIGHT_DP = 360.dp
internal val WORKSPACE_MARGIN_DP = 28.dp

/** All the state for one loaded model: its container position/size,
 *  interaction/label state, rotation/zoom, and its 3D node. */
class ModelInstance(
    val id: String,
    val displayName: String,
    val node: ModelNode,
    /** Plain transform node that owns [node] as its only child and is what
     *  actually gets rotated/scaled (see [applyTransform]). [node] itself is
     *  offset by `-modelCenter` at load time so the model's true geometric
     *  center lands on this pivot's origin - otherwise rotation would pivot
     *  around whatever the GLB's author happened to place at (0,0,0). */
    val pivotNode: Node,
    val labels: List<NodeLabel>,
    /** Radius of the smallest sphere (centered on the model's true geometric
     *  center, see [computeModelBounds]) that contains every mesh. Drives the
     *  auto-fit in [fitScale]. */
    val modelRadius: Float,
) {
    var containerOffset by mutableStateOf(Offset.Zero)

    // Reusing Offset as a (width, height) pair to avoid an extra import.
    var containerSize by mutableStateOf(Offset.Zero)
    var interactionMode by mutableStateOf(false)
    var labelsVisible by mutableStateOf(false)
    var screenLabels by mutableStateOf(emptyList<ScreenLabel>())

    // Gesture-accumulated; these drive the node directly and don't need to
    // be Compose State themselves.
    var rotX = 0f
    var rotY = 0f
    var zoom = 1f

    var lastRotX = -1f
    var lastRotY = -1f
    var lastZoom = -1f
    var lastSize = Offset.Infinite

    fun needsLabelRecompute(): Boolean {
        return rotX != lastRotX || rotY != lastRotY || zoom != lastZoom || containerSize != lastSize
    }

    fun updateLastState() {
        lastRotX = rotX
        lastRotY = rotY
        lastZoom = zoom
        lastSize = containerSize
    }
}

data class ScreenLabel(
    val anchor: Offset,
    val textPos: Offset,
    val text: String,
    val bounds: Rect = Rect.Zero
)

/** This container's own content (3D viewport) rect, in its own local pixel
 *  space - (0,0) is the top-left of the content area, below the header. */
internal fun ModelInstance.localContentRect(headerHeightPx: Float): Rect {
    val width = containerSize.x
    val height = (containerSize.y - headerHeightPx).coerceAtLeast(1f)
    return Rect(0f, 0f, width, height)
}

/** Uniform scale that fits this model's bounding sphere inside its own
 *  container, with [FIT_PADDING] of head-room. */
private fun ModelInstance.fitScale(headerHeightPx: Float, pixelToWorld: Float): Float {
    val content = localContentRect(headerHeightPx)
    val contentRadiusPx = min(content.width, content.height) / 2f
    val worldRadius = contentRadiusPx * pixelToWorld
    return worldRadius / (modelRadius * FIT_PADDING)
}

/** Pushes this instance's size/rotation/zoom state onto [pivotNode] - the
 *  one place Compose state becomes a Filament transform. Transforms
 *  [pivotNode] rather than [node] directly so rotation pivots around the
 *  model's real geometric center (see [pivotNode]'s doc comment) instead of
 *  its raw authored origin. */
internal fun ModelInstance.applyTransform(headerHeightPx: Float, pixelToWorld: Float) {
    pivotNode.position = Position(0f, 0f, 0f)
    pivotNode.scale = Scale(fitScale(headerHeightPx, pixelToWorld) * zoom)
    pivotNode.rotation = Rotation(x = rotX, y = rotY, z = 0f)
}

internal data class ModelBounds(val center: Position, val radius: Float)

/**
 * Walks the actual render hierarchy to find the model's true geometric
 * bounding-box center and radius, rather than trusting raw glTF accessor
 * min/max (`ModelNode.boundingBox`/`scaleToUnitCube`), which ignores
 * translations baked into intermediate nodes - solarsystem.glb parents each
 * planet mesh under an orbital-radius translation, so the raw accessor
 * bounds would measure roughly "one planet near the origin" instead of the
 * whole system.
 *
 * Must run immediately after construction, before the node is
 * repositioned/rotated, so the result is in the model's native root space.
 */
internal fun computeModelBounds(node: ModelNode): ModelBounds {
    val corners = mutableListOf<Position>()
    node.renderableNodes.forEach { renderable ->
        val box = renderable.axisAlignedBoundingBox
        val c = box.center
        val h = box.halfExtent
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                for (sz in intArrayOf(-1, 1)) {
                    val corner = Position(c[0] + sx * h[0], c[1] + sy * h[1], c[2] + sz * h[2])
                    corners += renderable.getWorldPosition(corner)
                }
            }
        }
    }
    if (corners.isEmpty()) return ModelBounds(Position(0f, 0f, 0f), 1f)

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var maxZ = -Float.MAX_VALUE
    corners.forEach { p ->
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.z > maxZ) maxZ = p.z
    }
    val center = Position((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)

    var maxDistanceSq = 0f
    corners.forEach { p ->
        val dx = p.x - center.x
        val dy = p.y - center.y
        val dz = p.z - center.z
        val distanceSq = dx * dx + dy * dy + dz * dz
        if (distanceSq > maxDistanceSq) maxDistanceSq = distanceSq
    }
    val radius = if (maxDistanceSq > 1e-8f) sqrt(maxDistanceSq) else 1f
    return ModelBounds(center, radius)
}
