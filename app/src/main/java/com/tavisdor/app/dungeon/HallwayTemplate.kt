package com.tavisdor.app.dungeon

/**
 * Pre-authored hallway / corridor connecting two room doors. Has exactly two
 * endpoint anchors with orientation so the generator can snap them to compatible
 * room doors. Full schema is TBD.
 */
data class HallwayTemplate(
    val id: String,
    val widthCells: Int,
    val heightCells: Int,
)
