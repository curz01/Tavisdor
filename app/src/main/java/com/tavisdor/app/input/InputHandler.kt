package com.tavisdor.app.input

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.tavisdor.app.combat.CombatController
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.game.Game
import com.tavisdor.app.render.DungeonRenderer
import kotlin.math.floor

/**
 * Routes touches that land on [com.tavisdor.app.GameView].
 *
 *   - Drag: free camera pan (no fog-of-war bounds).
 *   - Double-tap: center camera on the party token and resume
 *     exploration follow-on-move.
 *   - Single tap: move / combat actions (unchanged).
 */
class InputHandler {

    var onCombatMoveNeedsConfirm: ((Cell) -> Unit)? = null

    private var gestureDetector: GestureDetector? = null
    private var touchView: View? = null
    private var touchGame: Game? = null

    fun onTouch(event: MotionEvent, view: View, game: Game): Boolean {
        touchView = view
        touchGame = game
        val detector = gestureDetector ?: GestureDetector(
            view.context,
            DungeonGestureListener(),
        ).also { gestureDetector = it }
        return detector.onTouchEvent(event)
    }

    private inner class DungeonGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            val view = touchView ?: return false
            val game = touchGame ?: return false
            val cellPx = cellPx(view, game)
            if (cellPx <= 0f) return false
            game.onUserCameraPan(distanceX / cellPx, distanceY / cellPx)
            view.invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val view = touchView ?: return false
            val game = touchGame ?: return false
            game.focusCameraOnParty()
            view.invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val view = touchView ?: return false
            val game = touchGame ?: return false
            return handleTap(e.x, e.y, view, game)
        }
    }

    private fun handleTap(screenX: Float, screenY: Float, view: View, game: Game): Boolean {
        val floor = game.floor ?: return false
        val cellPx = cellPx(view, game)
        if (cellPx <= 0f) return false

        val viewCx = view.width / 2f
        val viewCy = view.height / 2f
        val cellXf = (screenX - viewCx) / cellPx + game.camera.centerCellX
        val cellYf = (screenY - viewCy) / cellPx + game.camera.centerCellY
        val target = Cell(floor(cellXf).toInt(), floor(cellYf).toInt())

        val onFloor = target in floor.floorCells
        val onDoor = floor.doorAt(target) != null
        val onChest = floor.chestAt(target) != null
        if (!onFloor && !onDoor && !onChest) return false

        if (game.isCombatTargetSelectionActive()) {
            return game.handleCombatTargetTap(target)
        }

        if (game.combat != null) {
            val tappedEnemy = floor.enemyAt(target)
            if (tappedEnemy != null && tappedEnemy.isAlive && floor.isVisibleToParty(target)) {
                game.setSelectedEnemy(tappedEnemy)
                return true
            }

            val confirm = onCombatMoveNeedsConfirm
            if (confirm != null && game.wouldCombatMoveLockOthers(target)) {
                confirm(target)
                return true
            }

            val result = game.attemptPartyMoveInCombat(target)
            return result != CombatController.PartyMoveResult.REJECTED
        }

        val explorationEnemy = floor.enemyAt(target)
        if (
            explorationEnemy != null &&
            explorationEnemy.isAlive &&
            floor.isVisibleToParty(target)
        ) {
            game.setSelectedEnemy(explorationEnemy)
            return true
        }

        return game.requestMoveTo(target)
    }

    private fun cellPx(view: View, game: Game): Float {
        val density = view.resources.displayMetrics.density
        return DungeonRenderer.BASE_CELL_PX_DP * density * game.camera.scale
    }
}
