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
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Door
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.enemies.Enemy
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
class DungeonRenderer(private val assets: AssetManager) {

    /** px per dp. Set by [com.tavisdor.app.GameView] before the first draw. */
    var density: Float = 1f

    /**
     * Lazy cache for enemy sprites. Eagerly pre-loading every enemy's
     * art at startup would bloat memory once dozens of bestiary
     * entries land; instead, the first frame that references each
     * path loads it, the result (success or `null`) is cached, and
     * subsequent frames skip the AssetManager entirely.
     */
    private val enemySpriteCache: MutableMap<String, Bitmap?> = HashMap()

    /**
     * Generic fallback drawn when an enemy has no [walkSpriteAssets]
     * (or every entry failed to load). Pre-loaded because it's
     * shared across templates and would otherwise cycle through the
     * lazy cache every frame.
     */
    private val enemyPlaceholder: Bitmap? = tryLoadBitmap(assets, "sprites/monster_placeholder.png")
    private val enemyPlaceholderSrc: Rect = enemyPlaceholder
        ?.let { Rect(0, 0, it.width, it.height) }
        ?: Rect(0, 0, 1, 1)

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

    private val tileStairsUp: Bitmap? = tryLoadBitmap(assets, "sprites/tile_stairs_up.png")
    private val tileStairsUpSrc: Rect = tileStairsUp
        ?.let { Rect(0, 0, it.width, it.height) }
        ?: Rect(0, 0, 1, 1)

    /**
     * Pre-loaded frames for the animated "you've selected this
     * enemy" arrow that bobs above the currently selected
     * combat target. Ordered to match the user-authored
     * animation: `loc1 -> loc1b -> loc2 -> loc2b`, then loops.
     *
     * Loaded up-front (not lazily) because the strip cycles
     * every frame whenever an enemy is selected; lazy decoding
     * would stall the first-frame display the very first time
     * a hate sprite was tapped.
     */
    private val selectionMarkerFrames: Array<Bitmap?> = arrayOf(
        tryLoadBitmap(assets, "sprites/loc1.png"),
        tryLoadBitmap(assets, "sprites/loc1b.png"),
        tryLoadBitmap(assets, "sprites/loc2.png"),
        tryLoadBitmap(assets, "sprites/loc2b.png"),
    )

    /**
     * Cached source rects for [selectionMarkerFrames] so the
     * draw loop doesn't allocate a [Rect] per blit. Mirrors the
     * pattern used for the tile / portrait sprites above.
     */
    private val selectionMarkerSrcRects: Array<Rect> = Array(selectionMarkerFrames.size) { idx ->
        selectionMarkerFrames[idx]
            ?.let { Rect(0, 0, it.width, it.height) }
            ?: Rect(0, 0, 1, 1)
    }

    private val partyIcon: Bitmap? = tryLoadBitmap(assets, "sprites/party_icon.png")
    private val partyIconSrc: Rect = partyIcon
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
     * Fallback paints for the stairs-UP cell when `tile_stairs_up.png`
     * is missing. Yellow tint mirrors the authoring pixel color so
     * "this is the yellow tile" stays visually consistent until art
     * is dropped in.
     */
    private val stairsUpFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC0A030")
        alpha = 215
    }
    private val stairsUpLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2A2008")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /**
     * Overlay for OPEN connector cells (red pixels in the source PNGs that
     * have not yet been merged with another template). Drawn as a red
     * triangle whose tip points in the direction the room continues -
     * tells the player "exit this way; tap here to step into the next
     * room". The triangle disappears automatically the moment the
     * connector closes (i.e. a new room joined here), since
     * [Floor.openConnectors] is the source of truth.
     */
    /**
     * Exit-indicator "!" paints. Fill is the same bright red the
     * triangle used so the visual language stays consistent; halo is
     * a thick cream stroke so the glyph reads against both the dark
     * background and the brown floor tile.
     */
    private val exitMarkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF2A2A")
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val exitMarkHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1A0E04")
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * One-shot per-floor logging guard - we want one "exit cells
     * sample" line in logcat per floor, not 60 lines per second.
     */
    private var loggedConnectorsForFloor: Boolean = false
    private var lastLoggedFloorSeed: Long = Long.MIN_VALUE

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

    /**
     * Per-frame source rect for enemy walk-cycle bitmaps. Each
     * frame might be a different size, so we reset its bounds at
     * draw time rather than caching a fixed rect per template.
     */
    private val tmpEnemySrc = Rect()

    /** Procedural enemy fallback: filled disc + first letter glyph. */
    private val enemyFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val enemyFallbackLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFF1D6")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun draw(canvas: Canvas, width: Int, height: Int, game: Game) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        labelPaint.textSize = 14f * density
        val floor = game.floor
        if (floor == null) {
            labelPaint.textSize = 18f * density
            canvas.drawText("Tavisdor - Floor ${game.floorDepth}", width / 2f, 60f * density, labelPaint)
            return
        }
        if (floor.seed != lastLoggedFloorSeed) {
            lastLoggedFloorSeed = floor.seed
            loggedConnectorsForFloor = false
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
        // Fog-of-war: skip any cell whose owning room/hallway hasn't
        // been entered yet. The party always stands on a revealed cell
        // (recordVisited fires before every camera recenter), and
        // previously-explored placements stay revealed for the run.
        for (c in floor.floorCells) {
            if (c.x < minCx || c.x > maxCx || c.y < minCy || c.y > maxCy) continue
            if (!floor.isRevealed(c)) continue
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

        // ----- Exit indicators (red triangle pointing into unexplored area) -----
        // Two cases, both drawn the same way: a revealed cell at the
        // BOUNDARY of the explored area gets a red triangle pointing
        // outward.
        //   1. Neighbor is a placed floor cell that isn't revealed yet
        //      (a pre-stitched room behind a doorway / corridor): the
        //      triangle says "walk this way to reveal the next room".
        //   2. Neighbor is empty AND the cell itself is an open
        //      connector: the triangle says "tap here to spawn a new
        //      room into the void".
        // Either way, stepping onto the neighbor reveals the new
        // placement -> the boundary moves -> the triangle disappears
        // automatically on the next frame. This replaces the previous
        // open-connector-only loop, which was invisible whenever
        // FloorGenerator's pre-grow had consumed every connector that
        // touched the spawn room.
        if (!loggedConnectorsForFloor) {
            Log.d(
                TAG,
                "floor depth=${floor.depth} revealed=${floor.revealedCells.size} " +
                    "openConnectors=${floor.openConnectors.size}",
            )
            loggedConnectorsForFloor = true
        }
        // Cache "can this open connector grow anything right now?" per
        // frame so the same connector isn't re-evaluated 1-4 times
        // while scanning its four cardinal neighbors.
        val connectorLiveCache = HashMap<Cell, Boolean>()
        for (c in floor.revealedCells) {
            if (c.x < minCx || c.x > maxCx || c.y < minCy || c.y > maxCy) continue
            val isOpenConnector = c in floor.openConnectors
            // Direction-agnostic "!" marker: this cell is an exit IFF
            // either (a) a cardinal neighbor is a placed-but-hidden
            // floor cell (walk in to reveal it), or (b) the cell is an
            // open connector whose direction can actually grow the
            // dungeon right now. One marker per cell is enough; we
            // bail out of the direction scan as soon as we know.
            var shouldMark = false
            for (d in CARDINAL_DIRECTIONS) {
                val neighbor = Cell(c.x + d.x, c.y + d.y)
                val neighborIsFloor = neighbor in floor.floorCells
                if (neighborIsFloor && !floor.isRevealed(neighbor)) {
                    shouldMark = true
                    break
                }
                if (!neighborIsFloor && isOpenConnector) {
                    val live = connectorLiveCache.getOrPut(c) { game.canExtendAt(c) }
                    if (live) {
                        shouldMark = true
                        break
                    }
                }
            }
            if (shouldMark) {
                val sx = (c.x - cx) * cellPx + viewCx
                val sy = (c.y - cy) * cellPx + viewCy
                drawExitArrow(canvas, sx, sy, cellPx, c)
            }
        }

        // ----- Highlight the staircase cell(s) -----
        // floor.staircases is the only source of truth now; per design,
        // it is empty until the player has explored
        // Floor.staircaseThresholdForDepth(depth) cells AND keeps tapping
        // open connectors until a staircase-bearing template is placed.
        for (c in floor.staircases) {
            if (c.x < minCx || c.x > maxCx || c.y < minCy || c.y > maxCy) continue
            if (!floor.isRevealed(c)) continue
            drawStaircase(canvas, c, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Stairs UP (yellow pixel; back to previous floor) -----
        for (c in floor.stairsUp) {
            if (c.x < minCx || c.x > maxCx || c.y < minCy || c.y > maxCy) continue
            if (!floor.isRevealed(c)) continue
            drawStairsUp(canvas, c, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Doors -----
        for ((cell, door) in floor.doors) {
            if (cell.x < minCx || cell.x > maxCx || cell.y < minCy || cell.y > maxCy) continue
            if (!floor.isRevealed(cell)) continue
            drawDoor(canvas, cell, door, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Enemies -----
        // Drawn after the floor / doors / stairs so they sit on top
        // of tile sprites, and BEFORE the party so the chess-piece
        // token stays visually dominant in the rare case the party
        // and an enemy end up on the same cell (shouldn't happen,
        // but safer than the reverse z-order).
        for (enemy in floor.enemies) {
            if (enemy.hp <= 0) continue
            val ec = enemy.cell
            if (ec.x < minCx || ec.x > maxCx || ec.y < minCy || ec.y > maxCy) continue
            if (!floor.isRevealed(ec)) continue
            drawEnemy(canvas, enemy, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Selected-enemy marker -----
        // Drawn after enemies so the bobbing arrow always sits on
        // top of the goblin sprite underneath; gated on the
        // selected enemy being alive AND on-screen so we don't
        // animate something the player can't see.
        val selected = game.selectedEnemy
        if (selected != null && selected.isAlive) {
            val sc = selected.cell
            if (sc.x in minCx..maxCx && sc.y in minCy..maxCy && floor.isRevealed(sc)) {
                drawSelectionMarker(canvas, sc, cx, cy, cellPx, viewCx, viewCy)
            }
        }

        // ----- Party token -----
        drawPartyToken(canvas, floor.partyCell, cx, cy, cellPx, viewCx, viewCy)

        // Floor label, still useful while we have no proper HUD.
        labelPaint.textSize = 14f * density
        canvas.drawText("Floor ${game.floorDepth}", width / 2f, 24f * density, labelPaint)
    }

    /**
     * Paints a bright "!" inside [sx]/[sy]'s cell as an "exit / point
     * of interest" hint. Direction-agnostic: the symbol just signals
     * "this cell goes somewhere interesting" - the player decides
     * which way to walk based on layout context.
     *
     * Rendered as a filled red glyph with a dark halo so it stays
     * readable against any tile background, plus a coin-style idle
     * bounce (one half-sine hop every [EXIT_MARK_CYCLE_SEC]) ported
     * verbatim from Mine Cart Max. [cell] feeds a tiny per-cell phase
     * offset so a room with several markers doesn't bounce in lock-
     * step - reads as ambient motion, not a synchronized blink.
     */
    private fun drawExitArrow(
        canvas: Canvas,
        sx: Float, sy: Float,
        cellPx: Float,
        cell: Cell,
    ) {
        exitMarkFillPaint.textSize = cellPx * 0.75f
        exitMarkHaloPaint.textSize = cellPx * 0.75f
        exitMarkHaloPaint.strokeWidth = (cellPx * 0.10f).coerceAtLeast(3f)

        val cx = sx + cellPx / 2f
        val fm = exitMarkFillPaint.fontMetrics
        val baseY = sy + cellPx / 2f - (fm.ascent + fm.descent) / 2f
        val baselineY = baseY + exitMarkBounceOffsetY(cell)

        canvas.drawText("!", cx, baselineY, exitMarkHaloPaint)
        canvas.drawText("!", cx, baselineY, exitMarkFillPaint)
    }

    /**
     * Returns the vertical pixel offset for the "!" at [cell] this
     * frame. Mirrors `WorldCoin` idle-bounce in Mine Cart Max:
     *   - One half-sine "hop" of [EXIT_MARK_BOUNCE_DUR_SEC] every
     *     [EXIT_MARK_CYCLE_SEC] (= bounce + flat interval).
     *   - Amplitude scales with [density] so it reads the same on
     *     every screen DPI.
     *   - Negative result = up (Android Y is screen-down positive).
     *   - Per-cell phase offset keeps clustered markers out of sync.
     */
    private fun exitMarkBounceOffsetY(cell: Cell): Float {
        val nowSec = SystemClock.uptimeMillis() / 1000.0
        val phase = ((cell.x * 0.37 + cell.y * 0.91) % EXIT_MARK_CYCLE_SEC + EXIT_MARK_CYCLE_SEC) %
            EXIT_MARK_CYCLE_SEC
        val u = (nowSec + phase) % EXIT_MARK_CYCLE_SEC
        if (u >= EXIT_MARK_BOUNCE_DUR_SEC) return 0f
        val bounceProgress = (u / EXIT_MARK_BOUNCE_DUR_SEC).toFloat()
        return -sin(PI.toFloat() * bounceProgress) * EXIT_MARK_BOUNCE_AMP_DP * density
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

    private fun drawStairsUp(
        canvas: Canvas,
        cell: Cell,
        camCx: Float, camCy: Float,
        cellPx: Float,
        viewCx: Float, viewCy: Float,
    ) {
        val sx = (cell.x - camCx) * cellPx + viewCx
        val sy = (cell.y - camCy) * cellPx + viewCy
        dstRect.set(sx, sy, sx + cellPx, sy + cellPx)

        val sprite = tileStairsUp
        if (sprite != null) {
            canvas.drawBitmap(sprite, tileStairsUpSrc, dstRect, drawBitmapPaint)
        } else {
            // Fallback when tile_stairs_up.png hasn't been added yet: a
            // yellow-tint tile with three ASCENDING lines so the player
            // can tell "up" from "down" before art lands.
            canvas.drawRect(dstRect, stairsUpFillPaint)
            val pad = cellPx * 0.15f
            val step = cellPx * 0.18f
            for (i in 0..2) {
                // Reverse so the widest line is at top, narrowing
                // downward - reads as an upward chevron.
                val y = sy + pad + step * (2 - i)
                val inset = pad + step * i * 0.5f
                canvas.drawLine(sx + inset, y, sx + cellPx - inset, y, stairsUpLinePaint)
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

    /**
     * Renders one [Enemy] at its cell. Picks the current walk-cycle
     * frame from [EnemyTemplate.walkSpriteAssets] using wall-clock
     * time (modulo the frame duration), with a small per-cell
     * phase offset so neighboring enemies don't animate in lockstep.
     *
     * Fallback chain:
     *   1. Current walk-cycle frame (lazy-loaded into [enemySpriteCache]).
     *   2. Generic [enemyPlaceholder] (pre-loaded at construction).
     *   3. Procedural diamond + first-letter glyph - never crashes
     *      even if every PNG is missing.
     */
    private fun drawEnemy(
        canvas: Canvas,
        enemy: Enemy,
        camCx: Float, camCy: Float,
        cellPx: Float,
        viewCx: Float, viewCy: Float,
    ) {
        val cell = enemy.cell
        val topLeftX = (cell.x - camCx) * cellPx + viewCx
        val topLeftY = (cell.y - camCy) * cellPx + viewCy
        dstRect.set(topLeftX, topLeftY, topLeftX + cellPx, topLeftY + cellPx)

        val sprite = currentWalkSprite(enemy)
        if (sprite != null) {
            tmpEnemySrc.set(0, 0, sprite.width, sprite.height)
            canvas.drawBitmap(sprite, tmpEnemySrc, dstRect, drawBitmapPaint)
            return
        }
        if (enemyPlaceholder != null) {
            canvas.drawBitmap(enemyPlaceholder, enemyPlaceholderSrc, dstRect, drawBitmapPaint)
            return
        }

        // Truly nothing loaded - draw a procedural marker so the
        // enemy is at least visible (helps spot art-loading regressions).
        val cx = topLeftX + cellPx / 2f
        val cy = topLeftY + cellPx / 2f
        val r = cellPx * 0.35f
        enemyFallbackPaint.color = Color.parseColor("#FFB23A3A")
        canvas.drawCircle(cx, cy, r, enemyFallbackPaint)
        enemyFallbackLetterPaint.textSize = cellPx * 0.5f
        val letter = enemy.name.firstOrNull()?.toString() ?: "?"
        val fm = enemyFallbackLetterPaint.fontMetrics
        canvas.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f, enemyFallbackLetterPaint)
    }

    /**
     * Picks the current walk-cycle frame bitmap for [enemy]. Frame
     * index = `(uptime + cellPhase) / walkFrameDurationMs`; the
     * cell-based phase staggers adjacent enemies so a room full of
     * goblins doesn't bob in unison.
     */
    private fun currentWalkSprite(enemy: Enemy): Bitmap? {
        val frames = enemy.template.walkSpriteAssets
        if (frames.isEmpty()) return null
        if (frames.size == 1) return loadEnemySprite(frames[0])
        val frameMs = enemy.template.walkFrameDurationMs.toLong().coerceAtLeast(1L)
        val phase = (enemy.cell.x * 31 + enemy.cell.y * 17).toLong()
        val idx = (((SystemClock.uptimeMillis() + phase) / frameMs).toInt() % frames.size + frames.size) % frames.size
        return loadEnemySprite(frames[idx])
    }

    private fun loadEnemySprite(path: String): Bitmap? =
        enemySpriteCache.getOrPut(path) { tryLoadBitmap(assets, path) }

    /**
     * Paints the bobbing "look here" arrow above [cell] using the
     * pre-loaded [selectionMarkerFrames]. The arrow points down,
     * so the marker is anchored so its bottom edge meets the
     * top of the target cell - the tip just kisses the goblin
     * underneath.
     *
     * Animation: cycles through the 4 frames at
     * [SELECTION_MARKER_FRAME_MS] each (`loc1 -> loc1b -> loc2 ->
     * loc2b -> loc1 ...`). Driven by wall-clock time so the
     * animation runs independently of combat tick cadence.
     *
     * Silent no-op when every sprite failed to decode - no
     * fallback glyph because the marker is purely a UX hint,
     * not a gameplay-critical sprite.
     */
    private fun drawSelectionMarker(
        canvas: Canvas,
        cell: Cell,
        camCx: Float, camCy: Float,
        cellPx: Float,
        viewCx: Float, viewCy: Float,
    ) {
        val frameIdx = (
            SystemClock.uptimeMillis() / SELECTION_MARKER_FRAME_MS
            ).toInt().mod(selectionMarkerFrames.size)
        val sprite = selectionMarkerFrames[frameIdx] ?: return

        val topLeftX = (cell.x - camCx) * cellPx + viewCx
        val topLeftY = (cell.y - camCy) * cellPx + viewCy
        // Marker is sized to [SELECTION_MARKER_CELL_FRACTION] of
        // a cell on each side, then centered horizontally above
        // the enemy cell so the arrow's tip kisses the top of
        // the enemy sprite without crowding the cells around it.
        val size = cellPx * SELECTION_MARKER_CELL_FRACTION
        val cx = topLeftX + cellPx / 2f
        val left = cx - size / 2f
        val right = cx + size / 2f
        val bottom = topLeftY
        val top = bottom - size
        dstRect.set(left, top, right, bottom)
        canvas.drawBitmap(sprite, selectionMarkerSrcRects[frameIdx], dstRect, drawBitmapPaint)
    }

    private fun drawPartyToken(
        canvas: Canvas,
        cell: Cell,
        camCx: Float, camCy: Float,
        cellPx: Float,
        viewCx: Float, viewCy: Float,
    ) {
        val topLeftX = (cell.x - camCx) * cellPx + viewCx
        val topLeftY = (cell.y - camCy) * cellPx + viewCy
        val sprite = partyIcon
        if (sprite != null) {
            // Fill the whole cell so the icon visually anchors to the
            // grid - same approach as tile_floor / tile_door /
            // tile_stairs_*. Tightly-cropped art reads fine; loose art
            // can be padded with transparency in the source PNG.
            dstRect.set(topLeftX, topLeftY, topLeftX + cellPx, topLeftY + cellPx)
            canvas.drawBitmap(sprite, partyIconSrc, dstRect, drawBitmapPaint)
        } else {
            // Fallback for the (unlikely) case where party_icon.png
            // failed to decode at startup; keeps the old procedural
            // ring so the party is at least visible.
            val sx = topLeftX + cellPx / 2f
            val sy = topLeftY + cellPx / 2f
            val outer = cellPx * 0.38f
            canvas.drawCircle(sx, sy, outer, partyRingPaint)
            canvas.drawCircle(sx, sy, outer * 0.62f, partyInnerPaint)
        }
    }

    companion object {
        private const val TAG = "DungeonRenderer"

        /**
         * Base dungeon cell size in dp at camera.scale = 1. Public so input
         * handlers can run the inverse (screen-pixel -> grid-cell) transform
         * without each owning a duplicate constant.
         */
        const val BASE_CELL_PX_DP = 60.375f

        /**
         * Idle-bounce timing for the "!" exit markers, ported from
         * `WorldCoin` in Mine Cart Max: one half-sine hop of
         * [EXIT_MARK_BOUNCE_DUR_SEC] every [EXIT_MARK_CYCLE_SEC]
         * (the rest of the cycle is flat). Amplitude is in dp so the
         * effect reads the same regardless of screen density.
         */
        private const val EXIT_MARK_BOUNCE_DUR_SEC: Double = 0.22
        private const val EXIT_MARK_BOUNCE_INTERVAL_SEC: Double = 1.0
        private const val EXIT_MARK_CYCLE_SEC: Double =
            EXIT_MARK_BOUNCE_DUR_SEC + EXIT_MARK_BOUNCE_INTERVAL_SEC
        private const val EXIT_MARK_BOUNCE_AMP_DP: Float = 5.5f

        /**
         * Milliseconds each frame of the selected-enemy arrow
         * (`loc1 / loc1b / loc2 / loc2b`) stays on screen before
         * the cycle advances. 352ms gives a roughly 1.4-second
         * full loop - 25% faster than the prior 440ms / 1.75-s
         * pacing, so the bob reads as a bit more alert without
         * crossing into frantic.
         */
        private const val SELECTION_MARKER_FRAME_MS: Long = 352L

        /**
         * Side length of the selected-enemy marker expressed as a
         * fraction of a single dungeon cell. 0.425 = 15% smaller
         * than the previous 0.5; keeps the arrow readable on
         * phone screens while letting adjacent tiles breathe.
         */
        private const val SELECTION_MARKER_CELL_FRACTION: Float = 0.425f

        /**
         * Unit step vectors checked when looking for outward neighbors
         * of a revealed cell while drawing exit indicators. Order
         * doesn't matter for correctness; the first matching direction
         * wins for the "open connector pointing at the void" case.
         */
        private val CARDINAL_DIRECTIONS = arrayOf(
            Cell(1, 0),
            Cell(-1, 0),
            Cell(0, 1),
            Cell(0, -1),
        )

        private fun tryLoadBitmap(assets: AssetManager, path: String): Bitmap? {
            return runCatching {
                assets.open(path).use { BitmapFactory.decodeStream(it) }
            }.onFailure {
                Log.w(TAG, "Failed to load sprite $path: ${it.message}")
            }.getOrNull()
        }
    }
}
