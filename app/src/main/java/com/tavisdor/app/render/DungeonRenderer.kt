package com.tavisdor.app.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Door
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.game.Game

/**
 * Draws the dungeon view inside [com.tavisdor.app.GameView].
 *
 * Rendering model:
 *   - The dungeon is a grid of cells. Each floor cell is rendered as one
 *     tile (currently `assets/sprites/tile_floor.png`) -- interior floor,
 *     connector, door, and staircase cells share that base sprite, with
 *     special-cell overlays drawn on top.
 *   - Staircase cells get the `assets/sprites/tile_stairs_down.png`
 *     sprite drawn on top of the floor sprite.
 *   - Door cells get the `assets/sprites/tile_door.png` sprite drawn on
 *     top of the floor sprite. Locked doors additionally get a small
 *     brass lock dot so locked / unlocked is still readable at a glance.
 *   - Open connectors (un-merged red pixels) get a small red square so the
 *     player knows where the dungeon can grow.
 *   - The party token is drawn as a placeholder ring on top of its cell.
 *
 * Camera transform:
 *   cellSize     = [BASE_CELL_PX] * camera.scale * density
 *   cellScreenX  = (cellX - camera.centerCellX) * cellSize + viewW/2
 *   cellScreenY  = (cellY - camera.centerCellY) * cellSize + viewH/2
 *
 * Construct with an [AssetManager] so the floor sprite can be loaded once at
 * startup (no per-frame decode).
 */
class DungeonRenderer(assets: AssetManager) {

    /** px per dp. Set by [com.tavisdor.app.GameView] before the first draw. */
    var density: Float = 1f

    private val tileFloor: Bitmap? = tryLoadBitmap(assets, "sprites/tile_floor.png")
    private val tileFloorSrc: Rect = tileFloor
        ?.let { Rect(0, 0, it.width, it.height) }
        ?: Rect(0, 0, 1, 1)

    private val tileDoor: Bitmap? = tryLoadBitmap(assets, "sprites/tile_door.png")
    private val tileDoorSrc: Rect = tileDoor
        ?.let { Rect(0, 0, it.width, it.height) }
        ?: Rect(0, 0, 1, 1)

    private val tileStairs: Bitmap? = tryLoadBitmap(assets, "sprites/tile_stairs_down.png")
    private val tileStairsSrc: Rect = tileStairs
        ?.let { Rect(0, 0, it.width, it.height) }
        ?: Rect(0, 0, 1, 1)

    // ----- Paints -----

    private val bgPaint = Paint().apply { color = Color.parseColor("#FF0E0B07") }

    /** Used when the floor sprite failed to load; draws solid blocks instead. */
    private val floorFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6E5A46")
    }

    private val staircaseFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5060A0")
        alpha = 200
    }
    private val staircaseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCFD6FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /**
     * Overlay for OPEN connector cells (red pixels in the source PNGs that
     * have not yet been merged with another template). Tells the player
     * "tap here to grow the dungeon". Cleared automatically when the
     * connector closes, since [Floor.openConnectors] is the source of truth.
     */
    private val openConnectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD33A3A")
        alpha = 200
    }

    private val doorUnlockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8B5A2B")
    }
    private val doorLockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD4A24A")
    }
    private val doorOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1A0F05")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val doorLockDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1A0F05")
    }

    private val partyRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEFE7D0")
    }
    private val partyInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C7A52")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB8AE92")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val drawBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()

    fun draw(canvas: Canvas, width: Int, height: Int, game: Game) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        labelPaint.textSize = 14f * density
        val floor = game.floor
        if (floor == null) {
            labelPaint.textSize = 18f * density
            canvas.drawText("Tavisdor - Floor ${game.floorDepth}", width / 2f, 60f * density, labelPaint)
            return
        }

        val camera = game.camera
        val cellPx = BASE_CELL_PX_DP * density * camera.scale
        val viewCx = width / 2f
        val viewCy = height / 2f
        val cx = camera.centerCellX
        val cy = camera.centerCellY

        // Compute the cell-coordinate window currently visible so we cull
        // anything offscreen without iterating the entire floorCells set.
        val halfCellsX = (viewCx / cellPx) + 1f
        val halfCellsY = (viewCy / cellPx) + 1f
        val minCx = (cx - halfCellsX).toInt() - 1
        val maxCx = (cx + halfCellsX).toInt() + 1
        val minCy = (cy - halfCellsY).toInt() - 1
        val maxCy = (cy + halfCellsY).toInt() + 1

        // ----- Draw floor cells -----
        for (c in floor.floorCells) {
            if (c.x < minCx || c.x > maxCx || c.y < minCy || c.y > maxCy) continue
            val sx = (c.x - cx) * cellPx + viewCx
            val sy = (c.y - cy) * cellPx + viewCy
            dstRect.set(sx, sy, sx + cellPx, sy + cellPx)
            val sprite = tileFloor
            if (sprite != null) {
                canvas.drawBitmap(sprite, tileFloorSrc, dstRect, drawBitmapPaint)
            } else {
                canvas.drawRect(dstRect, floorFallbackPaint)
            }
        }

        // ----- Open connector markers (tap to extend the dungeon) -----
        if (floor.openConnectors.isNotEmpty()) {
            val markerInset = cellPx * 0.32f
            for (c in floor.openConnectors) {
                if (c.x < minCx || c.x > maxCx || c.y < minCy || c.y > maxCy) continue
                val sx = (c.x - cx) * cellPx + viewCx
                val sy = (c.y - cy) * cellPx + viewCy
                dstRect.set(sx + markerInset, sy + markerInset, sx + cellPx - markerInset, sy + cellPx - markerInset)
                canvas.drawRect(dstRect, openConnectorPaint)
            }
        }

        // ----- Highlight the staircase cell(s) -----
        // floor.staircases is the only source of truth now; per design,
        // it is empty until the player has explored
        // Floor.staircaseThresholdForDepth(depth) cells AND keeps tapping
        // open connectors until a staircase-bearing template is placed.
        for (c in floor.staircases) {
            if (c.x < minCx || c.x > maxCx || c.y < minCy || c.y > maxCy) continue
            drawStaircase(canvas, c, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Doors -----
        for ((cell, door) in floor.doors) {
            if (cell.x < minCx || cell.x > maxCx || cell.y < minCy || cell.y > maxCy) continue
            drawDoor(canvas, cell, door, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Party token -----
        drawPartyToken(canvas, floor.partyCell, cx, cy, cellPx, viewCx, viewCy)

        // Floor label, still useful while we have no proper HUD.
        labelPaint.textSize = 14f * density
        canvas.drawText("Floor ${game.floorDepth}", width / 2f, 24f * density, labelPaint)
    }

    private fun drawStaircase(
        canvas: Canvas,
        cell: Cell,
        camCx: Float, camCy: Float,
        cellPx: Float,
        viewCx: Float, viewCy: Float,
    ) {
        val sx = (cell.x - camCx) * cellPx + viewCx
        val sy = (cell.y - camCy) * cellPx + viewCy
        dstRect.set(sx, sy, sx + cellPx, sy + cellPx)

        val sprite = tileStairs
        if (sprite != null) {
            canvas.drawBitmap(sprite, tileStairsSrc, dstRect, drawBitmapPaint)
        } else {
            // Fallback when tile_stairs_down.png failed to load: the older
            // procedural blue-tint + three descending lines.
            canvas.drawRect(dstRect, staircaseFillPaint)
            val pad = cellPx * 0.15f
            val step = cellPx * 0.18f
            for (i in 0..2) {
                val y = sy + pad + step * i
                canvas.drawLine(sx + pad, y, sx + cellPx - pad, y, staircaseLinePaint)
            }
        }
    }

    private fun drawDoor(
        canvas: Canvas,
        cell: Cell,
        door: Door,
        camCx: Float, camCy: Float,
        cellPx: Float,
        viewCx: Float, viewCy: Float,
    ) {
        val sx = (cell.x - camCx) * cellPx + viewCx
        val sy = (cell.y - camCy) * cellPx + viewCy
        dstRect.set(sx, sy, sx + cellPx, sy + cellPx)

        val sprite = tileDoor
        if (sprite != null) {
            canvas.drawBitmap(sprite, tileDoorSrc, dstRect, drawBitmapPaint)
        } else {
            // Fallback when tile_door.png failed to load: the older
            // procedural vertical bar so doors are still visible.
            val barW = cellPx * 0.34f
            val padY = cellPx * 0.08f
            val left = sx + (cellPx - barW) / 2f
            val top = sy + padY
            dstRect.set(left, top, left + barW, sy + cellPx - padY)
            canvas.drawRect(dstRect, if (door.locked) doorLockedPaint else doorUnlockedPaint)
            canvas.drawRect(dstRect, doorOutlinePaint)
        }

        if (door.locked) {
            // Small brass keyhole dot drawn on top of the sprite so locked
            // and unlocked states are still distinguishable at a glance.
            val cxd = sx + cellPx / 2f
            val cyd = sy + cellPx / 2f
            canvas.drawCircle(cxd, cyd, cellPx * 0.10f, doorLockedPaint)
            canvas.drawCircle(cxd, cyd, cellPx * 0.10f, doorOutlinePaint)
        }
    }

    private fun drawPartyToken(
        canvas: Canvas,
        cell: Cell,
        camCx: Float, camCy: Float,
        cellPx: Float,
        viewCx: Float, viewCy: Float,
    ) {
        val sx = (cell.x - camCx) * cellPx + viewCx + cellPx / 2f
        val sy = (cell.y - camCy) * cellPx + viewCy + cellPx / 2f
        val outer = cellPx * 0.38f
        canvas.drawCircle(sx, sy, outer, partyRingPaint)
        canvas.drawCircle(sx, sy, outer * 0.62f, partyInnerPaint)
    }

    companion object {
        private const val TAG = "DungeonRenderer"

        /**
         * Base dungeon cell size in dp at camera.scale = 1. Public so input
         * handlers can run the inverse (screen-pixel -> grid-cell) transform
         * without each owning a duplicate constant.
         */
        const val BASE_CELL_PX_DP = 52.5f

        private fun tryLoadBitmap(assets: AssetManager, path: String): Bitmap? {
            return runCatching {
                assets.open(path).use { BitmapFactory.decodeStream(it) }
            }.onFailure {
                Log.w(TAG, "Failed to load sprite $path: ${it.message}")
            }.getOrNull()
        }
    }
}
