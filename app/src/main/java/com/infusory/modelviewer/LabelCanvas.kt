package com.infusory.modelviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas

/** Draws one model's labels onto a Canvas sized to just its own content
 *  area. No layout or projection math here - [LabelProjector] already
 *  computed the [ScreenLabel]s this just draws. */
@Composable
fun LabelCanvas(instance: ModelInstance) {
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }

    if (!instance.labelsVisible) return

    Canvas(Modifier.fillMaxSize()) {
        instance.screenLabels.forEach { label ->
            val connectorEnd = LabelProjector.closestPointOnRect(label.bounds, label.anchor)

            drawPath(
                path = Path().apply {
                    moveTo(label.anchor.x, label.anchor.y)
                    lineTo(connectorEnd.x, connectorEnd.y)
                },
                color = AppColors.LabelConnector.copy(alpha = 0.85f),
                style = Stroke(width = 1.5f)
            )

            drawCircle(
                color = AppColors.LabelConnector,
                radius = 3.5f,
                center = label.anchor
            )

            drawRoundRect(
                color = AppColors.LabelBackground,
                topLeft = label.textPos,
                size = label.bounds.size,
                cornerRadius = CornerRadius(8f, 8f)
            )

            drawRoundRect(
                color = AppColors.LabelBorder,
                topLeft = label.textPos,
                size = label.bounds.size,
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 1f)
            )

            drawContext.canvas.nativeCanvas.drawText(
                label.text,
                label.textPos.x + label.bounds.width / 2f,
                label.textPos.y + label.bounds.height / 2f + 8f,
                textPaint
            )
        }
    }
}
