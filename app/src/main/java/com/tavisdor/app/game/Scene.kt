package com.tavisdor.app.game

/**
 * Reserved for a future scene abstraction (e.g. distinct combat / exploration /
 * cutscene scenes if the renderer's responsibilities grow). For now the simple
 * [Game.Mode] enum is sufficient and this interface is unused - kept as a
 * placeholder so adding richer scene composition later is a non-breaking edit.
 */
interface Scene {
    /** Per-frame tick; return true to request a redraw. */
    fun update(dtSec: Float): Boolean = false
}
