package com.tavisdor.app

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.tavisdor.app.game.Game
import com.tavisdor.app.input.InputHandler
import com.tavisdor.app.render.DungeonRenderer

/**
 * Top portion of the in-game screen: renders the dungeon view and routes touch
 * input. Hosts a [Choreographer]-driven loop that calls [Game.tickAnimations]
 * each frame and invalidates the dungeon view continuously while a [Game] is
 * attached.
 *
 * Continuous invalidation is required for wall-clock animations that aren't
 * tied to game-state changes:
 *   - Enemy walk-cycle frames ([com.tavisdor.app.render.DungeonRenderer.currentWalkSprite])
 *     index off `SystemClock.uptimeMillis()`. Stop redrawing and the goblin
 *     sprite freezes mid-stride.
 *   - Exit-arrow "!" bounce in exploration mode and similar idle effects.
 *
 * Sibling HUD views (turn-order strip, hero panel) use the [onFrameTick]
 * callback's `needsRedraw` argument to avoid redrawing every frame - that
 * value still reflects whether the game-state machine actually changed
 * (combat animation step, hero turn handoff, etc.).
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private var game: Game? = null
    private val renderer = DungeonRenderer(context.assets).also {
        it.density = resources.displayMetrics.density
    }
    private val input = InputHandler()

    private var lastFrameNanos: Long = 0L
    private var loopActive = false

    /**
     * Per-frame hook fired after [Game.tickAnimations] runs. Sibling
     * views that need to redraw in lockstep with the dungeon (e.g.
     * the turn-order strip during combat) subscribe here so they
     * don't have to spin up their own [Choreographer] loop.
     *
     * The boolean argument is the `redraw` value the game returned
     * for this frame - subscribers can short-circuit if nothing
     * happened.
     */
    var onFrameTick: ((needsRedraw: Boolean) -> Unit)? = null

    fun attachGame(g: Game) {
        game = g
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loopActive = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        loopActive = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!loopActive) return
        val dtSec = if (lastFrameNanos == 0L) 0f else (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameTimeNanos

        val g = game
        val needsRedraw = g != null && g.tickAnimations(dtSec)
        // Invalidate every frame while a game is attached so the
        // dungeon renderer can pick up wall-clock animations
        // (enemy walk cycle, idle bounces) regardless of whether
        // the game-state machine changed this frame. The cost is
        // negligible - when the gameRoot overlay is GONE (title
        // screen, etc.) Android short-circuits the actual draw.
        if (g != null) invalidate()
        onFrameTick?.invoke(needsRedraw)
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val g = game ?: return
        renderer.draw(canvas, width, height, g)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = game ?: return false
        return input.onTouch(event, this, g)
    }
}
