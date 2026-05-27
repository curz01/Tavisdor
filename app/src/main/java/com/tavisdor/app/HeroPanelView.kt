package com.tavisdor.app

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.tavisdor.app.game.Game
import com.tavisdor.app.render.HeroPanelRenderer
/**
 * Persistent bottom strip showing the four heroes in a 2x2 grid:
 *   row 0 = front line (slots 0, 1)
 *   row 1 = back line  (slots 2, 3)
 *
 * Tapping anywhere inside a hero cell fires [onHeroCellTapped] with
 * the slot index so the activity can open the skill-assignment panel.
 * The tap also marks that hero as the active selection (white border)
 * for the top Action button.
 */
class HeroPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var game: Game? = null
    private val renderer = HeroPanelRenderer(context.assets).also {
        it.density = resources.displayMetrics.density
    }

    /** Fires with slot index 0..3 when any part of a hero cell is tapped. */
    var onHeroCellTapped: ((Int) -> Unit)? = null

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var tracking: Boolean = false

    fun attachGame(g: Game) {
        game = g
        // The active-slot blink lives on Game; redraw whenever it
        // changes so the white border appears/disappears immediately
        // instead of waiting for the next animation tick.
        g.onActiveHeroSlotChanged = { invalidate() }
        // Threat-level label tracks the player's currently-selected
        // enemy: redraw immediately when the selection changes so
        // the label row updates the same frame the player taps a new
        // goblin, instead of waiting for the next animation tick.
        g.onSelectedEnemyChanged = { invalidate() }
        g.onSkillStagingChanged = { invalidate() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width, height, game)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tracking = true
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > slop.toFloat() * slop) return false

                // Whole cell (portrait, bars, ACT/GRD buttons, blank
                // space) opens skill assignment and marks the hero active.
                val cellSlot = renderer.hitTestCell(event.x, event.y, width, height)
                if (cellSlot >= 0 && game?.party?.heroes?.getOrNull(cellSlot) != null) {
                    game?.setActiveHeroSlot(cellSlot)
                    onHeroCellTapped?.invoke(cellSlot)
                    return true
                }

                false
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                false
            }
            else -> false
        }
    }
}
