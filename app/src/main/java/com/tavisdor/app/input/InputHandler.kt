package com.tavisdor.app.input

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.tavisdor.app.combat.CombatController
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.game.Game
import com.tavisdor.app.render.DungeonRenderer
import kotlin.math.floor

/**
 * Routes touches that land on [com.tavisdor.app.GameView].
 *
 * Currently implemented:
 *   - Out of combat: tap on ANY walkable floor cell -> ask
 *     [Game.requestMoveTo] for the shortest 4-directional path;
 *     the per-frame mover in [Game.tickAnimations] then walks the
 *     party one cell at a time, halting if combat triggers along
 *     the way. Tapping a fresh cell during a move retargets the
 *     party. Open-connector expansion still fires whenever the
 *     party lands on an open red pixel.
 *   - In combat: tap on a CARDINAL-adjacent walkable cell ->
 *     [Game.attemptPartyMoveInCombat], which runs the disengage
 *     check (if any adjacent enemy is touching the party) and
 *     either steps the party or eats the active hero's turn.
 *     Non-adjacent taps are ignored silently to keep combat from
 *     hijacking accidental taps.
 *
 * Not yet implemented (separate tasks):
 *   - Drag = camera pan ([com.tavisdor.app.render.Camera.centerCellX/Y]).
 *   - Pinch = camera zoom ([com.tavisdor.app.render.Camera.scale]).
 *   - Descend prompt when stepping onto a staircase.
 *
 * Tap detection uses the system [ViewConfiguration.scaledTouchSlop] so a
 * touch that moves more than the slop between DOWN and UP is treated as a
 * (future) drag and ignored.
 */
class InputHandler {

    /**
     * Invoked when a combat-mode tap on a legal one-cell move
     * target would also forfeit at least one OTHER hero's turn
     * this round (relocating the party costs the whole roster's
     * action). The host is expected to show a confirmation
     * dialog and, on accept, call [Game.attemptPartyMoveInCombat]
     * with the same [Cell] - InputHandler does NOT auto-commit
     * the move because we don't want a stray tap to silently
     * burn three hero turns.
     *
     * Null disables the prompt: in that case any qualifying tap
     * just goes through to [Game.attemptPartyMoveInCombat]
     * directly. The bottom-row consequence still fires (every
     * remaining hero turn is locked) - the player just doesn't
     * get to opt out. Production builds should always set this.
     */
    var onCombatMoveNeedsConfirm: ((Cell) -> Unit)? = null

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var trackingDown: Boolean = false

    fun onTouch(event: MotionEvent, view: View, game: Game): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                trackingDown = true
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!trackingDown) return false
                trackingDown = false
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > slop.toFloat() * slop) return false
                handleTap(event.x, event.y, view, game)
            }
            MotionEvent.ACTION_CANCEL -> {
                trackingDown = false
                false
            }
            else -> false
        }
    }

    private fun handleTap(screenX: Float, screenY: Float, view: View, game: Game): Boolean {
        val floor = game.floor ?: return false
        val density = view.resources.displayMetrics.density
        val cellPx = DungeonRenderer.BASE_CELL_PX_DP * density * game.camera.scale
        if (cellPx <= 0f) return false

        // Inverse of the renderer's cell -> screen transform:
        //   screenX = (cellX - cam.centerX) * cellPx + viewW/2
        // -> cellX = floor((screenX - viewW/2) / cellPx + cam.centerX)
        val viewCx = view.width / 2f
        val viewCy = view.height / 2f
        val cellXf = (screenX - viewCx) / cellPx + game.camera.centerCellX
        val cellYf = (screenY - viewCy) / cellPx + game.camera.centerCellY
        val target = Cell(floor(cellXf).toInt(), floor(cellYf).toInt())

        if (target !in floor.floorCells) return false

        // Branch on game mode. Combat has its own movement rules
        // (single-cell, disengage check on adjacent enemies); the
        // exploration mover handles paths of any length.
        if (game.combat != null) {
            if (game.isCombatTargetSelectionActive()) {
                return game.handleCombatTargetTap(target)
            }

            // Enemy-cell tap: select the enemy so its hate values
            // populate the hero-panel hate icons. We check this
            // BEFORE attemptPartyMoveInCombat because that call
            // rejects enemy-occupied cells outright, so without
            // this branch tap-to-select would never fire.
            val tappedEnemy = floor.enemyAt(target)
            if (tappedEnemy != null && tappedEnemy.isAlive && floor.isVisibleToParty(target)) {
                game.setSelectedEnemy(tappedEnemy)
                return true
            }

            // Defer to the confirmation prompt when the move
            // would burn other heroes' turns. The callback is
            // expected to call [Game.attemptPartyMoveInCombat]
            // itself on accept, so we DON'T commit here -
            // returning true marks the touch as consumed (the
            // dialog will land on the next frame).
            val confirm = onCombatMoveNeedsConfirm
            if (confirm != null && game.wouldCombatMoveLockOthers(target)) {
                confirm(target)
                return true
            }

            val result = game.attemptPartyMoveInCombat(target)
            // REJECTED covers "not your turn", "not cardinal-
            // adjacent", "blocked by wall/enemy/locked door" -
            // intentionally silent so a stray tap doesn't toast.
            // SURROUNDED already posted a log entry; the other
            // results all moved the party or ended the turn,
            // which the controller's tick loop will pick up on
            // the next frame.
            return result != CombatController.PartyMoveResult.REJECTED
        }

        // requestMoveTo handles every case: same-cell (rejected), adjacent
        // (1-step path), and far (multi-step BFS). The Choreographer loop
        // in GameView picks the move up on the next frame and invalidates
        // itself when it advances, so we don't need to invalidate here.
        return game.requestMoveTo(target)
    }
}
