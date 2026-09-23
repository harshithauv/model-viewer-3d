package com.infusory.modelviewer

import androidx.compose.ui.graphics.Color

/**
 * Centralized "dark navy + blue accent" palette, so every screen, container
 * and label uses the same handful of colors instead of scattering hex
 * literals across files.
 */
internal object AppColors {
    val Background = Color(0xFF0A0E17)
    val Surface = Color(0xFF111827)
    val SurfaceElevated = Color(0xFF17203A)
    val Border = Color(0x33FFFFFF)
    val BorderStrong = Color(0x55FFFFFF)

    val Accent = Color(0xFF3B82F6)
    val AccentVariant = Color(0xFF2563EB)
    val AccentOnAccent = Color.White // text/icon color drawn on top of a filled Accent surface

    val TextPrimary = Color.White
    val TextSecondary = Color(0xB3FFFFFF)

    /** Very subtle tint over a container's 3D content - just enough to read
     *  as a "card", not enough to meaningfully hide the model rendered
     *  underneath (that surface is shared across every model - see
     *  ModelViewerScreen.kt - so anything close to opaque here would hide
     *  every model, not just decorate one). */
    val ContainerTint = Color(0x14FFFFFF)

    val LabelBackground = Color(0xF00F1420)
    val LabelBorder = Color(0x80FFFFFF)
    val LabelConnector = Accent
}
