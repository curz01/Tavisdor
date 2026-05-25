package com.tavisdor.app

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.tavisdor.app.game.Game
import com.tavisdor.app.render.HeroPanelRenderer
import com.tavisdor.app.skills.SkillButton

/**
 * Persistent bottom strip showing the four heroes in a 2x2 grid:
 *   row 0 = front line (slots 0, 1)
 *   row 1 = back line  (slots 2, 3)
 *
 * Tapping a hero portrait fires [onHeroPortraitTapped] with the slot
 * index. The activity uses that to open the hero detail panel.
 * Taps on the action buttons (ACT / GRD / SPL) are not handled yet -
 * combat hasn't been wired up.
 */
class HeroPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var game: Game? = null
    private val renderer = HeroPanelRenderer().also {
        it.density = resources.displayMetrics.density
    }

    /** Fires with slot index 0..3 when a portrait square is tapped. */
    var onHeroPortraitTapped: ((Int) -> Unit)? = null

    /**
     * Fires when one of the 3 action buttons (ACT / GRD / SPL) is
     * tapped. Receives the hero slot (0..3) plus which button was hit.
     * The activity uses this to pop a skill-selection dialog.
     */
    var onHeroActionButtonTapped: ((slot: Int, button: SkillButton) -> Unit)? = null

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var tracking: Boolean = false

    fun attachGame(g: Game) {
        game = g
        // The active-slot blink lives on Game; redraw whenever it
        // changes so the white border appears/disappears immediately
        // instead of waiting for the next animation tick.
        g.onActiveHeroSlotChanged = { invalidate() }
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

                // Hit-test order is most-specific to least-specific:
                //   1. ACT / GRD / SPL buttons (open skill picker)
                //   2. Portrait square         (open detail panel)
                //   3. Anywhere else in the cell (selection-only)
                // In ALL three cases we also mark that hero as active
                // so the top-bar Action button has a target and the
                // white blink border appears on the tapped cell.
                val btnHit = renderer.hitTestActionButton(event.x, event.y, width, height)
                if (btnHit != null && game?.party?.heroes?.getOrNull(btnHit.slot) != null) {
                    game?.setActiveHeroSlot(btnHit.slot)
                    onHeroActionButtonTapped?.invoke(btnHit.slot, btnHit.button)
                    return true
                }

                val portraitSlot = renderer.hitTestPortraitSlot(event.x, event.y, width, height)
                if (portraitSlot >= 0 && game?.party?.heroes?.getOrNull(portraitSlot) != null) {
                    game?.setActiveHeroSlot(portraitSlot)
                    onHeroPortraitTapped?.invoke(portraitSlot)
                    return true
                }

                // Fallback: tap anywhere inside a cell selects that
                // hero without opening any panel. Useful when the
                // player wants to retarget the top-bar Action button
                // without firing any of the cell's interactive bits.
                val cellSlot = renderer.hitTestCell(event.x, event.y, width, height)
                if (cellSlot >= 0 && game?.party?.heroes?.getOrNull(cellSlot) != null) {
                    game?.setActiveHeroSlot(cellSlot)
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
