package com.infusory.modelviewer

/** The five models provided with the task, bundled in assets/models/. */
data class CatalogEntry(val displayName: String, val assetPath: String)

object ModelCatalog {
    val entries = listOf(
        CatalogEntry("Light Bulb", "models/Bulb.glb"),
        CatalogEntry("Fiagena", "models/Fiagena.glb"),
        CatalogEntry("Lungs", "models/Lungs.glb"),
        CatalogEntry("Microscope", "models/Microscope.glb"),
        CatalogEntry("Solar System", "models/solarsystem.glb"),
    )
}
