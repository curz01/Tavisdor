package com.tavisdor.app.render

/**
 * Dungeon-view camera. Decoupled from the party so the player can pan freely
 * over previously-explored rooms without moving anyone.
 *
 * [centerCellX] / [centerCellY] are the dungeon-grid cell coordinates (may be
 * fractional) currently at the screen's center. [scale] is a pixel multiplier
 * on top of the renderer's base cell size. Identity defaults: centered on
 * (0, 0), 1x zoom.
 *
 * The camera supports two centering modes:
 *   - [centerOn] / [snapTo] - instant cut. Used by exploration-mode auto-move
 *     and floor descents because those happen at a brisk cadence and a tween
 *     would visibly lag the party.
 *   - [panTo] + [tick] - smooth tween over a duration. Combat uses this so
 *     the camera can pan to an active enemy, follow it step by step, and
 *     return to the party for player turns without snapping.
 *
 * [panTo] sets a target and starts the easing; [tick] is called once per
 * frame by the game loop to advance the easing by [deltaMs]. Any [centerOn]
 * call interrupts an in-flight pan - exploration cuts always win.
 */
class Camera {
    var centerCellX: Float = 0f
    var centerCellY: Float = 0f
    var scale: Float = 1f

    // ---- Pan tween state ----
    //
    // panRemainingMs > 0 -> a smooth pan is in progress. Each [tick] call
    // decrements the remaining time and interpolates centerCellX/Y from
    // (panFromX, panFromY) toward (panTargetX, panTargetY) along a
    // smoothstep curve. When the remaining time hits zero the camera
    // snaps to the target so floating-point drift can't leave it short.
    private var panFromX: Float = 0f
    private var panFromY: Float = 0f
    private var panTargetX: Float = 0f
    private var panTargetY: Float = 0f
    private var panDurationMs: Float = 1f
    private var panRemainingMs: Float = 0f

    /** True iff a smooth pan is currently easing toward a target. */
    val isPanning: Boolean get() = panRemainingMs > 0f

    fun reset() {
        centerCellX = 0f
        centerCellY = 0f
        scale = 1f
        panRemainingMs = 0f
    }

    /**
     * Snap the camera to the given dungeon cell. Cancels any in-flight
     * smooth pan; the next [panTo] will start from this position. Does
     * not change [scale].
     */
    fun centerOn(cellX: Int, cellY: Int) {
        centerCellX = cellX.toFloat()
        centerCellY = cellY.toFloat()
        panRemainingMs = 0f
    }

    /**
     * Same as [centerOn] but accepts a fractional position - useful when
     * combat ends mid-pan and we want to settle exactly on the party's
     * cell with no easing.
     */
    fun snapTo(cellX: Float, cellY: Float) {
        centerCellX = cellX
        centerCellY = cellY
        panRemainingMs = 0f
    }

    /** Stops an in-flight [panTo] tween without moving the center. */
    fun cancelPan() {
        panRemainingMs = 0f
    }

    /**
     * Free pan in cell space (used by touch drag). Cancels any active
     * smooth pan first.
     */
    fun panByCellDelta(deltaX: Float, deltaY: Float) {
        cancelPan()
        centerCellX += deltaX
        centerCellY += deltaY
    }

    /**
     * Start a smooth pan from the current center toward ([cellX], [cellY])
     * over [durationMs] milliseconds (clamped to a minimum of 1 to avoid
     * divide-by-zero). Replaces any in-flight pan - the new pan starts
     * from wherever the camera currently sits, so chaining `panTo`s
     * doesn't snap.
     *
     * A zero-or-negative [durationMs] is treated as a snap (calls
     * [snapTo] directly) so callers don't have to special-case it.
     */
    fun panTo(cellX: Float, cellY: Float, durationMs: Float) {
        if (durationMs <= 0f) {
            snapTo(cellX, cellY)
            return
        }
        panFromX = centerCellX
        panFromY = centerCellY
        panTargetX = cellX
        panTargetY = cellY
        panDurationMs = durationMs
        panRemainingMs = durationMs
    }

    /**
     * Advance an active pan by [deltaMs] millis. Returns true when the
     * camera moved this tick (so the host knows to redraw); false when
     * idle. Safe to call when no pan is active.
     */
    fun tick(deltaMs: Long): Boolean {
        if (panRemainingMs <= 0f) return false
        panRemainingMs -= deltaMs.toFloat()
        if (panRemainingMs <= 0f) {
            centerCellX = panTargetX
            centerCellY = panTargetY
            panRemainingMs = 0f
            return true
        }
        // Smoothstep ease: t * t * (3 - 2t). Symmetric ease-in/ease-out
        // matches how the player will read the camera "settling" onto
        // a target rather than slamming into it.
        val elapsed = panDurationMs - panRemainingMs
        val tLin = (elapsed / panDurationMs).coerceIn(0f, 1f)
        val tEase = tLin * tLin * (3f - 2f * tLin)
        centerCellX = panFromX + (panTargetX - panFromX) * tEase
        centerCellY = panFromY + (panTargetY - panFromY) * tEase
        return true
    }
}
