package com.infusory.modelviewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.launch
import kotlin.math.min

// Screen orchestration: owns the list of loaded models and the shared
// Filament engine/model loader, and wires the per-model UI together.
// Gesture logic lives in ModelGestures.kt; per-model transform math in
// ModelInstance.kt.

@Composable
fun ModelViewerScreen() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val instances = remember { mutableStateListOf<ModelInstance>() }
    var selectedInstanceId by remember { mutableStateOf<String?>(null) }
    var rootSizePx by remember { mutableStateOf(IntSize.Zero) }
    // Virtual vertical scroll position of the workspace, in px - not a real
    // Compose ScrollView. Every container's displayed position just
    // subtracts this offset.
    var scrollOffsetPx by remember { mutableStateOf(0f) }
    val headerHeightPx = with(density) { HEADER_HEIGHT_DP.toPx() }
    val baseContainerSizePx = with(density) { BASE_CONTAINER_SIZE_DP.toPx() }
    val minContainerPx = with(density) { MIN_CONTAINER_DP.toPx() }
    val maxContainerPx = with(density) { MAX_CONTAINER_DP.toPx() }
    val rowHeightPx = with(density) { ROW_HEIGHT_DP.toPx() }
    val workspaceMarginPx = with(density) { WORKSPACE_MARGIN_DP.toPx() }
    // Converts a container's content radius in px into the world-space
    // radius the same fixed camera distance/FOV sees filling it.
    val pixelToWorld = 1f / baseContainerSizePx

    /** How far the workspace is allowed to scroll: just enough to reveal
     *  the bottom-most edge of whatever's actually on screen right now
     *  (including containers the user has dragged below their starting
     *  row), clamped to 0 when everything already fits in the viewport. */
    fun maxScrollPx(): Float {
        val contentBottom = instances.maxOfOrNull { it.containerOffset.y + it.containerSize.y } ?: 0f
        val workspaceHeight = maxOf(rootSizePx.height.toFloat(), contentBottom + workspaceMarginPx)
        return (workspaceHeight - rootSizePx.height).coerceAtLeast(0f)
    }

    /** Keeps a drag from placing any part of the frame outside the
     *  workspace horizontally; vertically it can scroll/grow, so only the
     *  top edge is bounded at 0. */
    fun clampContainerOffset(size: Offset, offset: Offset): Offset {
        val minX = 0f
        val maxX = (rootSizePx.width - size.x).coerceAtLeast(minX)
        val maxY = rootSizePx.height * 3f
        return Offset(offset.x.coerceIn(minX, maxX), offset.y.coerceIn(0f, maxY))
    }

    /** Largest (width, height) a frame centered on [center] could grow to,
     *  symmetrically in both directions, while staying inside the visible
     *  workspace. */
    fun maxContainerSizeAt(center: Offset): Offset {
        val maxWidth = (2f * min(center.x, rootSizePx.width - center.x)).coerceAtLeast(minContainerPx)
        val currentScrollPx = scrollOffsetPx.coerceIn(0f, maxScrollPx())
        val screenCenterY = center.y - currentScrollPx
        val maxHeight = (2f * min(screenCenterY, rootSizePx.height - screenCenterY)).coerceAtLeast(minContainerPx)
        return Offset(maxWidth, maxHeight)
    }

    /** Finds a top-to-bottom, left-to-right free position for a new frame:
     *  fully inside the workspace and not overlapping any existing model
     *  (with [WORKSPACE_MARGIN_DP] of breathing room). Candidates are the
     *  workspace's own top/left edge plus every existing frame's
     *  right/bottom edge, rather than a dense pixel scan. Falls back to a
     *  new row below everything if nothing else fits. */
    fun findFreePosition(width: Float, height: Float): Offset {
        val margin = workspaceMarginPx

        fun fitsAt(x: Float, y: Float): Boolean {
            if (x < 0f || y < 0f || x + width > rootSizePx.width) return false
            val candidate = Rect(x, y, x + width, y + height)
            return instances.none { existing ->
                val other = Rect(
                    existing.containerOffset.x - margin,
                    existing.containerOffset.y - margin,
                    existing.containerOffset.x + existing.containerSize.x + margin,
                    existing.containerOffset.y + existing.containerSize.y + margin
                )
                candidate.overlaps(other)
            }
        }

        val candidateYs = (listOf(margin) +
            instances.map { it.containerOffset.y + it.containerSize.y + margin }).distinct().sorted()
        val candidateXs = (listOf(margin) +
            instances.map { it.containerOffset.x + it.containerSize.x + margin }).distinct().sorted()

        for (y in candidateYs) {
            for (x in candidateXs) {
                if (fitsAt(x, y)) return Offset(x, y)
            }
        }

        val fallbackY = (instances.maxOfOrNull { it.containerOffset.y + it.containerSize.y } ?: 0f) + margin
        return Offset(margin, fallbackY)
    }

    val effectiveScrollPx = scrollOffsetPx.coerceIn(0f, maxScrollPx())

    // Shared across every model - the genuinely expensive resources. Each
    // model's own View/Scene/Camera/Renderer live in its own container.
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    fun applyTransform(instance: ModelInstance) {
        instance.applyTransform(headerHeightPx, pixelToWorld)
    }

    fun addModel(entry: CatalogEntry) {
        scope.launch {
            val modelInstanceData = modelLoader.createModelInstance(assetFileLocation = entry.assetPath)
            // No scaleToUnits: scale is managed ourselves via
            // ModelInstance.applyTransform/fitScale.
            val node = ModelNode(modelInstance = modelInstanceData)
            // Must run before the node is repositioned below, while its
            // transform is still identity.
            val bounds = computeModelBounds(node)
            // Recenter so the model's true geometric center sits at the
            // pivot node's origin - see ModelInstance.pivotNode.
            node.position = Position(-bounds.center.x, -bounds.center.y, -bounds.center.z)
            val pivotNode = Node(engine)
            pivotNode.addChildNode(node)
            val labels = GlbLabelParser.parseLabels(context, entry.assetPath)

            val startWidthPx = (rootSizePx.width - 2 * workspaceMarginPx)
                .coerceIn(minContainerPx, maxContainerPx)
            val startHeightPx = rowHeightPx
            val startOffsetPx = findFreePosition(startWidthPx, startHeightPx)

            val instance = ModelInstance(
                id = "${entry.assetPath}-${System.nanoTime()}",
                displayName = entry.displayName,
                node = node,
                pivotNode = pivotNode,
                labels = labels,
                modelRadius = bounds.radius
            )
            instance.containerOffset = startOffsetPx
            instance.containerSize = Offset(startWidthPx, startHeightPx)
            instances.add(instance)
            selectedInstanceId = instance.id
            applyTransform(instance)

            // Auto-scroll just enough to bring the new frame on screen.
            val viewportHeight = rootSizePx.height.toFloat()
            val frameTop = startOffsetPx.y
            val frameBottom = frameTop + startHeightPx
            scrollOffsetPx = when {
                frameTop < scrollOffsetPx -> frameTop - workspaceMarginPx
                frameBottom > scrollOffsetPx + viewportHeight -> frameBottom - viewportHeight + workspaceMarginPx
                else -> scrollOffsetPx
            }.coerceIn(0f, maxScrollPx())
        }
    }

    /** Removes one model's container and releases only its own resources;
     *  every other loaded model is untouched. */
    fun removeModel(instance: ModelInstance) {
        instances.remove(instance)
        instance.screenLabels = emptyList()
        try {
            instance.node.destroy()
            instance.pivotNode.destroy()
        } catch (_: Exception) {
            // Worst case this leaks one Filament model; it won't crash the app.
        }
        scrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollPx())
    }

    val sheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)
    BackHandler(enabled = sheetState.isVisible) {
        scope.launch { sheetState.hide() }
    }

    ModalBottomSheetLayout(
        sheetState = sheetState,
        sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        sheetBackgroundColor = AppColors.Surface,
        sheetContent = {
            ModelPickerSheet(
                onPick = { entry ->
                    scope.launch { sheetState.hide() }
                    addModel(entry)
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                MainTopBar(
                    loadedCount = instances.size,
                    onAddModel = { scope.launch { sheetState.show() } }
                )
            },
            backgroundColor = AppColors.Background
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .onGloballyPositioned { rootSizePx = it.size }
            ) {
                // Background catcher: sits below every ModelContainer in
                // z-order, so a touch inside any container never reaches it.
                Box(
                    Modifier
                        .fillMaxSize()
                        .workspaceScrollGestures(
                            scrollOffsetPx = { scrollOffsetPx },
                            maxScrollPx = { maxScrollPx() },
                            onScrollChange = { updated -> scrollOffsetPx = updated }
                        )
                )

                instances.forEach { instance ->
                    ModelContainer(
                        instance = instance,
                        isSelected = selectedInstanceId == instance.id,
                        headerHeightDp = HEADER_HEIGHT_DP,
                        minContainerPx = minContainerPx,
                        maxContainerPx = maxContainerPx,
                        scrollOffsetPx = effectiveScrollPx,
                        engine = engine,
                        modelLoader = modelLoader,
                        maxSizeFor = { offset -> maxContainerSizeAt(offset) },
                        clampOffset = { offset -> clampContainerOffset(instance.containerSize, offset) },
                        onSelect = { selectedInstanceId = instance.id },
                        onTransformChanged = { applyTransform(instance) },
                        onToggleInteraction = { instance.interactionMode = !instance.interactionMode },
                        onToggleLabels = {
                            instance.labelsVisible = !instance.labelsVisible
                            if (!instance.labelsVisible) instance.screenLabels = emptyList()
                        },
                        onClose = {
                            if (selectedInstanceId == instance.id) selectedInstanceId = null
                            removeModel(instance)
                        }
                    )
                }

                if (instances.isEmpty()) {
                    EmptyState(onAddModel = { scope.launch { sheetState.show() } })
                }
            }
        }
    }
}

@Composable
private fun MainTopBar(loadedCount: Int, onAddModel: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("3D Model Viewer", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (loadedCount == 0) "No models loaded" else "$loadedCount model${if (loadedCount > 1) "s" else ""} loaded",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary
                )
            }
        },
        actions = {
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.Accent)
                    .clickable(onClick = onAddModel)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add model",
                    tint = AppColors.AccentOnAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Add Model", color = AppColors.AccentOnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        },
        backgroundColor = AppColors.Surface,
        contentColor = AppColors.TextPrimary,
        elevation = 8.dp
    )
}

@Composable
private fun EmptyState(onAddModel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = AppColors.Accent.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(16.dp))
            Text("3D Model Viewer", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Text(
                "Add a model to start inspecting it",
                fontSize = 16.sp,
                color = AppColors.TextSecondary
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAddModel,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.height(48.dp).padding(horizontal = 32.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Model")
            }
        }
    }
}

/** Small, purely cosmetic mapping so the "Select Model" sheet doesn't show
 *  five identical icons - keyed on the catalog's own display names rather
 *  than adding a field to [ModelCatalog], so the catalog stays a plain
 *  data list. */
private fun iconForEntry(entry: CatalogEntry): ImageVector = when {
    entry.displayName.contains("Solar", ignoreCase = true) -> Icons.Default.Public
    entry.displayName.contains("Bulb", ignoreCase = true) -> Icons.Default.Lightbulb
    entry.displayName.contains("Lung", ignoreCase = true) -> Icons.Default.Favorite
    entry.displayName.contains("Microscope", ignoreCase = true) -> Icons.Default.Search
    else -> Icons.Default.Settings
}

@Composable
private fun ModelPickerSheet(onPick: (CatalogEntry) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            "Select Model",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        ModelCatalog.entries.forEach { entry ->
            Card(
                shape = RoundedCornerShape(12.dp),
                backgroundColor = AppColors.SurfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onPick(entry) },
                elevation = 0.dp
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(AppColors.Accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(iconForEntry(entry), contentDescription = null, tint = AppColors.Accent)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(entry.displayName, color = AppColors.TextPrimary, fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
