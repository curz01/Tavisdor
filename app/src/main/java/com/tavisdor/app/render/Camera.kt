package com.tavisdor.app.render

/**
 * Dungeon-view camera. Decoupled from the party so the player can pan freely
 * over previously-explored rooms without moving anyone.
 *
 * [centerCellX] / [centerCellY] are the dungeon-grid cell coordinates (may be
 * fractional) currently at the screen's center. [scale] is a pixel multiplier
 * on top of the renderer's base cell size. Identity defaults: centered on
 * (0, 0), 1x zoom.
 */
class Camera {
    var centerCellX: Float = 0f
    var centerCellY: Float = 0f
    var scale: Float = 1f

    fun reset() {
        centerCellX = 0f
        centerCellY = 0f
        scale = 1f
    }

    /** Center the camera on the given dungeon cell. Does not change [scale]. */
    fun centerOn(cellX: Int, cellY: Int) {
        centerCellX = cellX.toFloat()
        centerCellY = cellY.toFloat()
    }
}
