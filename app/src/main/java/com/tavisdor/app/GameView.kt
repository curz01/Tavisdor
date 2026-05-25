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
 * each frame and invalidates only when the game reports that something visible
 * has changed. Turn-based logic itself does not require per-frame redraws -
 * the loop exists so tween animations (e.g. party walking one cell to the next)
 * can drive smooth interpolation without a separate timer.
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
        if (g != null && g.tickAnimations(dtSec)) {
            invalidate()
        }
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
