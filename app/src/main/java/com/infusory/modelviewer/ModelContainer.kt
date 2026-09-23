package com.infusory.modelviewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.google.android.filament.Engine
import io.github.sceneview.Scene
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView
import kotlin.math.roundToInt

/**
 * UI for one model's card: a toolbar (name + interaction toggle, label
 * toggle, close) above a 3D viewport. Gesture handling lives only on the
 * viewport box below, so tapping a toolbar button is never misread as a
 * drag/rotate.
 */
@Composable
fun BoxScope.ModelContainer(
    instance: ModelInstance,
    isSelected: Boolean,
    headerHeightDp: Dp,
    minContainerPx: Float,
    maxContainerPx: Float,
    scrollOffsetPx: Float,
    engine: Engine,
    modelLoader: ModelLoader,
    maxSizeFor: (Offset) -> Offset,
    clampOffset: (Offset) -> Offset,
    onSelect: () -> Unit,
    onTransformChanged: () -> Unit,
    onToggleInteraction: () -> Unit,
    onToggleLabels: () -> Unit,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    val widthDp = with(density) { instance.containerSize.x.toDp() }
    val heightDp = with(density) { instance.containerSize.y.toDp() }
    val headerHeightPx = with(density) { headerHeightDp.toPx() }
    val cardShape = RoundedCornerShape(16.dp)

    Column(
        Modifier
            .offset {
                IntOffset(
                    instance.containerOffset.x.roundToInt(),
                    (instance.containerOffset.y - scrollOffsetPx).roundToInt()
                )
            }
            .size(widthDp, heightDp)
            .zIndex(if (isSelected) 1f else 0f)
            .shadow(if (isSelected) 10.dp else 3.dp, cardShape)
            .clip(cardShape)
            .background(AppColors.ContainerTint)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) AppColors.Accent else AppColors.Border,
                shape = cardShape
            )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(headerHeightDp)
                .background(AppColors.SurfaceElevated)
                .pointerInput(instance.id) { detectTapGestures { onSelect() } }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                instance.displayName,
                color = AppColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ControlIcon(
                icon = Icons.Default.Rotate90DegreesCcw,
                active = instance.interactionMode,
                contentDescription = "Interaction mode"
            ) {
                onSelect()
                onToggleInteraction()
            }
            Spacer(Modifier.width(6.dp))
            ControlIcon(
                icon = Icons.Default.Label,
                active = instance.labelsVisible,
                contentDescription = "Show or hide labels"
            ) {
                onSelect()
                onToggleLabels()
            }
            Spacer(Modifier.width(6.dp))
            ControlIcon(
                icon = Icons.Default.Close,
                active = false,
                accent = AppColors.TextSecondary,
                contentDescription = "Close model",
                onClick = onClose
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
        ) {
            Model3DViewport(
                instance = instance,
                engine = engine,
                modelLoader = modelLoader,
                headerHeightPx = headerHeightPx
            )

            LabelCanvas(instance)

            Box(
                Modifier
                    .fillMaxSize()
                    .modelContainerGestures(
                        instance = instance,
                        minContainerPx = minContainerPx,
                        maxContainerPx = maxContainerPx,
                        maxSizeFor = maxSizeFor,
                        clampOffset = clampOffset,
                        onSelect = onSelect,
                        onTransformChanged = onTransformChanged
                    )
            )
        }
    }
}

/**
 * This model's own dedicated Filament `View`/`Scene`/`Camera`/`Renderer` -
 * sharing only the app-wide `Engine`/`ModelLoader`. Sized to exactly this
 * Box via `Modifier.fillMaxSize()`, so the model can't draw a pixel outside
 * it at any zoom or rotation. Each `remember*` call registers its own
 * `DisposableEffect` that tears down the Filament object when this
 * composable leaves composition (i.e. when the model is closed).
 */
@Composable
private fun Model3DViewport(
    instance: ModelInstance,
    engine: Engine,
    modelLoader: ModelLoader,
    headerHeightPx: Float
) {
    val view = rememberView(engine)
    val renderer = rememberRenderer(engine)
    val scene = rememberScene(engine)
    val collisionSystem = rememberCollisionSystem(view)
    val cameraNode = rememberCameraNode(engine) { position = Position(z = 6f) }
    val mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f }

    Scene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        view = view,
        renderer = renderer,
        scene = scene,
        collisionSystem = collisionSystem,
        cameraNode = cameraNode,
        mainLightNode = mainLightNode,
        childNodes = listOf(instance.pivotNode),
        // Disable SceneView's own default orbit/pan/zoom detector - it
        // would otherwise fight with Modifier.modelContainerGestures.
        cameraManipulator = null,
        onFrame = { recomputeLabelsIfNeeded(instance, cameraNode, view, headerHeightPx) }
    )
}

/** Circular toolbar icon button: outlined when off, filled when on. */
@Composable
private fun ControlIcon(
    icon: ImageVector,
    active: Boolean,
    contentDescription: String,
    accent: Color = AppColors.Accent,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (active) accent else Color.Transparent,
        border = if (active) null else BorderStroke(1.5.dp, accent.copy(alpha = 0.7f)),
        modifier = Modifier
            .size(28.dp)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(15.dp),
                tint = if (active) AppColors.AccentOnAccent else accent
            )
        }
    }
}
