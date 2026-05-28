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
import kotlin.math.min
import kotlin.math.sin
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Door
import com.tavisdor.app.dungeon.DoorAxis
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.combat.CombatTargeting
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
 *   - Door cells get axis- and state-specific sprites
 *     (`tile_door_ns_*` / `tile_door_ew_*`, closed vs opened) drawn on
 *     top of the floor sprite.
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

    private fun dp(v: Float): Float = v * density

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

    private val tileDoorNsClosed: Bitmap? =
        tryLoadBitmap(assets, "sprites/tile_door_ns_closed.png")
    private val tileDoorNsOpened: Bitmap? =
        tryLoadBitmap(assets, "sprites/tile_door_ns_opened.png")
    private val tileDoorEwClosed: Bitmap? =
        tryLoadBitmap(assets, "sprites/tile_door_ew_closed.png")
    private val tileDoorEwOpened: Bitmap? =
        tryLoadBitmap(assets, "sprites/tile_door_ew_opened.png")

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

    /**
     * Wall-clock anchor for the selected-enemy arrow intro. Reset
     * whenever [selectionMarkerTarget] changes so each new tap
     * replays the faster frame cycle + three vertical bounces,
     * then the marker hides for the rest of that selection.
     */
    private var selectionMarkerTarget: Enemy? = null
    private var selectionMarkerStartMs: Long = 0L

    private val partyIcon: Bitmap? = tryLoadBitmap(assets, "sprites/party_icon.png")
    private val partyIconSrc: Rect = partyIcon
        ?.let { Rect(0, 0, it.width, it.height) }
        ?: Rect(0, 0, 1, 1)

    /**
     * Party-token hop animation state. We observe [Floor.partyCell]
     * directly here (rather than wiring a callback through Game ->
     * CombatController -> exploration mover) so every movement
     * code path - exploration auto-walk, in-combat one-cell step,
     * Charge teleport - picks up the lift-and-drop visual for free.
     *
     * [lastSeenPartyCell] anchors change detection. When the cell
     * flips between frames, we capture the previous cell into
     * [partyStepFromCell] and stamp [partyStepStartMs] so the
     * draw routine can interpolate from old -> new over the next
     * [PARTY_HOP_DURATION_MS_*] window. Resetting these to (cell,
     * null, 0) on floor change keeps the spawn from firing a
     * phantom hop the first frame after `descend`.
     */
    private var lastSeenPartyCell: Cell? = null
    private var partyStepFromCell: Cell? = null
    private var partyStepStartMs: Long = 0L

    /**
     * Soft drop-shadow underneath the party token while it's
     * airborne mid-hop. Alpha is patched at draw time so the
     * shadow fades a touch at peak lift (reads as "piece is
     * higher off the board") and disappears entirely once the
     * piece settles back onto its cell.
     */
    private val partyShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    // ----- Paints -----

    private val bgPaint = Paint().apply { color = Color.parseColor("#FF0E0B07") }

    /** Used when the floor sprite failed to load; draws solid blocks instead. */
    private val floorFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6E5A46")
    }

    private val targetDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA000000")
        style = Paint.Style.FILL
    }
    private val targetInRangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3348B06A")
        style = Paint.Style.FILL
    }
    private val targetEnemyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66E6C12C")
        style = Paint.Style.FILL
    }
    private val targetSplashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6648A0E8")
        style = Paint.Style.FILL
    }
    private val targetEnemyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCE6C12C")
        style = Paint.Style.STROKE
        strokeWidth = 3f
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
    private val partyRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEFE7D0")
    }
    private val partyInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C7A52")
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

    private val enemyHpBarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0F0C08")
    }
    private val enemyHpBarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC03030")
    }
    private val enemyHpBarBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1A0F05")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun draw(canvas: Canvas, width: Int, height: Int, game: Game) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        enemyHpBarBorderPaint.strokeWidth = dp(1f)

        val floor = game.floor
        if (floor == null) {
            return
        }
        if (floor.seed != lastLoggedFloorSeed) {
            lastLoggedFloorSeed = floor.seed
            loggedConnectorsForFloor = false
            // New floor: the party "teleports" to its spawn cell.
            // Reset the hop tracker so the first frame doesn't try
            // to arc the icon from the OLD floor's last cell into
            // the NEW floor's spawn (which would also pick a bogus
            // shadow position halfway between two unrelated grids).
            lastSeenPartyCell = floor.partyCell
            partyStepFromCell = null
            partyStepStartMs = 0L
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
        // Fog-of-war: only placements the party has entered are drawn.
        for (c in floor.floorCells) {
            if (c.x < minCx || c.x > maxCx || c.y < minCy || c.y > maxCy) continue
            if (!floor.isVisibleToParty(c)) continue
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

        drawCombatTargetOverlay(
            canvas = canvas,
            game = game,
            floor = floor,
            cx = cx,
            cy = cy,
            cellPx = cellPx,
            viewCx = viewCx,
            viewCy = viewCy,
            minCx = minCx,
            maxCx = maxCx,
            minCy = minCy,
            maxCy = maxCy,
        )

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
            if (!floor.isVisibleToParty(c)) continue
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
            if (!floor.isVisibleToParty(c)) continue
            drawStaircase(canvas, c, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Stairs UP (yellow pixel; back to previous floor) -----
        for (c in floor.stairsUp) {
            if (c.x < minCx || c.x > maxCx || c.y < minCy || c.y > maxCy) continue
            if (!floor.isVisibleToParty(c)) continue
            drawStairsUp(canvas, c, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Doors -----
        for ((cell, door) in floor.doors) {
            if (cell.x < minCx || cell.x > maxCx || cell.y < minCy || cell.y > maxCy) continue
            if (!floor.isVisibleToParty(cell)) continue
            drawDoor(canvas, cell, door, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Enemies -----
        // Drawn after the floor / doors / stairs so they sit on top
        // of tile sprites, and BEFORE the party so the chess-piece
        // token stays visually dominant in the rare case the party
        // and an enemy end up on the same cell (shouldn't happen,
        // but safer than the reverse z-order).
        val defenderFx = game.defenderSpellFxGateway
        for (enemy in floor.enemies) {
            if (enemy.hp <= 0) continue
            val ec = enemy.cell
            if (ec.x < minCx || ec.x > maxCx || ec.y < minCy || ec.y > maxCy) continue
            if (!floor.isVisibleToParty(ec)) continue
            if (defenderFx.targets(enemy)) {
                defenderFx.drawBehindEnemy(canvas, enemy, cx, cy, cellPx, viewCx, viewCy)
            }
            val shake = defenderFx.shakeOffsetPx(enemy, cellPx)
            val spriteRect = drawEnemy(
                canvas = canvas,
                game = game,
                enemy = enemy,
                camCx = cx,
                camCy = cy,
                cellPx = cellPx,
                viewCx = viewCx,
                viewCy = viewCy,
                screenOffsetX = shake.first,
                screenOffsetY = shake.second,
            )
            drawEnemyHealthBar(canvas, spriteRect, enemy.hp, enemy.maxHp)
            if (defenderFx.targets(enemy)) {
                defenderFx.drawInFrontOfEnemy(
                    canvas, enemy, cx, cy, cellPx, viewCx, viewCy, spriteRect,
                )
            }
        }

        // ----- Selected-enemy marker -----
        // Drawn after enemies so the bobbing arrow always sits on
        // top of the goblin sprite underneath; gated on the
        // selected enemy being alive AND on-screen so we don't
        // animate something the player can't see.
        val selected = game.selectedEnemy
        if (selected != null && selected.isAlive && !game.isCombatTargetSelectionActive()) {
            val sc = selected.cell
            if (sc.x in minCx..maxCx && sc.y in minCy..maxCy && floor.isVisibleToParty(sc)) {
                syncSelectionMarkerClock(selected)
                if (isSelectionMarkerAnimating()) {
                    drawSelectionMarker(canvas, sc, cx, cy, cellPx, viewCx, viewCy)
                }
            }
        } else {
            clearSelectionMarkerClock()
        }

        // ----- Party token -----
        val partyCell = floor.partyCell
        val lunge = game.partyLungeVisualCell()
        if (lunge != null) {
            // Charge lunge: render the party at its tweened position
            // so multi-cell lunges don't read as teleports.
            drawPartyTokenAt(canvas, game, lunge.first, lunge.second, cx, cy, cellPx, viewCx, viewCy)
        } else {
            drawPartyToken(canvas, game, partyCell, cx, cy, cellPx, viewCx, viewCy)
        }

        // ----- Weapon attack FX (above party + enemies) -----
        game.drawWeaponAttackFx(canvas, cx, cy, cellPx, viewCx, viewCy)
    }

    /**
     * Dims revealed tiles outside the staged skill's range and tints
     * tiles the attack can reach. Enemy cells that can be targeted
     * get a stronger highlight + stroke (see [CombatTargeting]).
     */
    private fun drawCombatTargetOverlay(
        canvas: Canvas,
        game: Game,
        floor: Floor,
        cx: Float,
        cy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
        minCx: Int,
        maxCx: Int,
        minCy: Int,
        maxCy: Int,
    ) {
        val selection = game.combatTargetSelection ?: return
        val overlay = CombatTargeting.buildOverlayMap(
            floor = floor,
            origin = floor.partyCell,
            skill = selection.skill,
        )
        val inset = cellPx * 0.06f
        for (cell in floor.floorCells) {
            if (cell.x < minCx || cell.x > maxCx || cell.y < minCy || cell.y > maxCy) continue
            if (!floor.isVisibleToParty(cell)) continue
            val highlight = CombatTargeting.highlightForCell(overlay, cell)
            val sx = (cell.x - cx) * cellPx + viewCx
            val sy = (cell.y - cy) * cellPx + viewCy
            dstRect.set(sx + inset, sy + inset, sx + cellPx - inset, sy + cellPx - inset)
            when (highlight) {
                CombatTargeting.TileHighlight.DIMMED ->
                    canvas.drawRect(dstRect, targetDimPaint)
                CombatTargeting.TileHighlight.IN_RANGE ->
                    canvas.drawRect(dstRect, targetInRangePaint)
                CombatTargeting.TileHighlight.TARGETABLE_ENEMY -> {
                    canvas.drawRect(dstRect, targetEnemyPaint)
                    canvas.drawRect(dstRect, targetEnemyStrokePaint)
                }
                CombatTargeting.TileHighlight.SPLASH ->
                    canvas.drawRect(dstRect, targetSplashPaint)
            }
        }
    }

    private fun drawPartyTokenAt(
        canvas: Canvas,
        game: Game,
        visualCellX: Float,
        visualCellY: Float,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
    ) {
        val visualScreenX = (visualCellX - camCx) * cellPx + viewCx
        val visualScreenY = (visualCellY - camCy) * cellPx + viewCy

        val baseOffsetPx = cellPx * PARTY_ICON_BASE_OFFSET_FRACTION
        val restingBaseY = visualScreenY + cellPx - baseOffsetPx

        val iconWidth = cellPx * PARTY_ICON_WIDTH_FRACTION
        val iconHeight = cellPx * PARTY_ICON_HEIGHT_FRACTION

        val footprintCx = visualScreenX + cellPx / 2f
        val iconLeft = footprintCx - iconWidth / 2f
        val iconRight = footprintCx + iconWidth / 2f
        val iconBottom = restingBaseY
        val iconTop = iconBottom - iconHeight

        val sprite = partyIcon
        if (sprite != null) {
            dstRect.set(iconLeft, iconTop, iconRight, iconBottom)
            canvas.drawBitmap(sprite, partyIconSrc, dstRect, drawBitmapPaint)
        } else {
            val sx = footprintCx
            val sy = iconTop + iconHeight / 2f
            val outer = iconWidth * 0.38f
            canvas.drawCircle(sx, sy, outer, partyRingPaint)
            canvas.drawCircle(sx, sy, outer * 0.62f, partyInnerPaint)
        }
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

        val sprite = doorSprite(door)
        if (sprite != null) {
            doorSpriteSrc.set(0, 0, sprite.width, sprite.height)
            canvas.drawBitmap(sprite, doorSpriteSrc, dstRect, drawBitmapPaint)
        } else {
            drawDoorFallback(canvas, door, sx, sy, cellPx)
        }
    }

    private val doorSpriteSrc = Rect(0, 0, 1, 1)

    private fun doorSprite(door: Door): Bitmap? = when (door.axis) {
        DoorAxis.NS -> if (door.locked) tileDoorNsClosed else tileDoorNsOpened
        DoorAxis.EW -> if (door.locked) tileDoorEwClosed else tileDoorEwOpened
    }

    /** Procedural placeholder when door art is missing. */
    private fun drawDoorFallback(
        canvas: Canvas,
        door: Door,
        sx: Float,
        sy: Float,
        cellPx: Float,
    ) {
        val pad = cellPx * 0.08f
        when (door.axis) {
            DoorAxis.NS -> {
                val barH = cellPx * 0.34f
                val left = sx + pad
                val top = sy + (cellPx - barH) / 2f
                dstRect.set(left, top, sx + cellPx - pad, top + barH)
            }
            DoorAxis.EW -> {
                val barW = cellPx * 0.34f
                val left = sx + (cellPx - barW) / 2f
                val top = sy + pad
                dstRect.set(left, top, left + barW, sy + cellPx - pad)
            }
        }
        canvas.drawRect(dstRect, if (door.locked) doorLockedPaint else doorUnlockedPaint)
        canvas.drawRect(dstRect, doorOutlinePaint)
    }

    /**
     * Renders one [Enemy] at its visual grid position. During combat
     * move tweens the position comes from [CombatController.visualPositionFor]
     * so the sprite + HP bar slide with the cinematic step.
     *
     * Returns the screen-space bounds of the drawn sprite so the
     * HP bar can match its width and sit just above it.
     *
     * Fallback chain:
     *   1. Current walk-cycle frame (lazy-loaded into [enemySpriteCache]).
     *   2. Generic [enemyPlaceholder] (pre-loaded at construction).
     *   3. Procedural disc + first-letter glyph - never crashes
     *      even if every PNG is missing.
     */
    private fun drawEnemy(
        canvas: Canvas,
        game: Game,
        enemy: Enemy,
        camCx: Float, camCy: Float,
        cellPx: Float,
        viewCx: Float, viewCy: Float,
        screenOffsetX: Float = 0f,
        screenOffsetY: Float = 0f,
    ): RectF {
        val (vx, vy) = enemyVisualPosition(game, enemy)
        val cellTopLeftX = (vx - camCx) * cellPx + viewCx + screenOffsetX
        val cellTopLeftY = (vy - camCy) * cellPx + viewCy + screenOffsetY
        val spriteRect = computeEnemySpriteRect(enemy, cellTopLeftX, cellTopLeftY, cellPx)

        val sprite = currentWalkSprite(enemy)
        if (sprite != null) {
            tmpEnemySrc.set(0, 0, sprite.width, sprite.height)
            canvas.drawBitmap(sprite, tmpEnemySrc, spriteRect, drawBitmapPaint)
            return spriteRect
        }
        if (enemyPlaceholder != null) {
            canvas.drawBitmap(enemyPlaceholder, enemyPlaceholderSrc, spriteRect, drawBitmapPaint)
            return spriteRect
        }

        val cx = (spriteRect.left + spriteRect.right) / 2f
        val cy = (spriteRect.top + spriteRect.bottom) / 2f
        val r = spriteRect.width() * 0.35f
        enemyFallbackPaint.color = Color.parseColor("#FFB23A3A")
        canvas.drawCircle(cx, cy, r, enemyFallbackPaint)
        enemyFallbackLetterPaint.textSize = spriteRect.height() * 0.5f
        val letter = enemy.name.firstOrNull()?.toString() ?: "?"
        val fm = enemyFallbackLetterPaint.fontMetrics
        canvas.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f, enemyFallbackLetterPaint)
        return spriteRect
    }

    /**
     * Fractional grid position used for drawing [enemy]. During
     * [EnemyStep.Move] tweens this lerps between cells; otherwise
     * it's the enemy's logical [Enemy.cell].
     */
    private fun enemyVisualPosition(game: Game, enemy: Enemy): Pair<Float, Float> {
        val controller = game.combatController
        if (controller != null) return controller.visualPositionFor(enemy)
        return enemy.cell.x.toFloat() to enemy.cell.y.toFloat()
    }

    /**
     * Screen-space sprite bounds for [enemy], preserving bitmap
     * aspect ratio and honoring [EnemyTemplate.spriteDisplayScale]
     * so larger monsters get wider/taller art AND a matching HP bar.
     * Feet anchor to the bottom-center of the occupied footprint.
     */
    private fun computeEnemySpriteRect(
        enemy: Enemy,
        cellTopLeftX: Float,
        cellTopLeftY: Float,
        cellPx: Float,
    ): RectF {
        val maxDim = cellPx * enemy.template.spriteDisplayScale
        val footprintCx = cellTopLeftX + cellPx / 2f
        val footprintBottom = cellTopLeftY + cellPx

        val sprite = currentWalkSprite(enemy) ?: enemyPlaceholder
        if (sprite != null && sprite.width > 0 && sprite.height > 0) {
            val bmpW = sprite.width.toFloat()
            val bmpH = sprite.height.toFloat()
            val scale = min(maxDim / bmpW, maxDim / bmpH)
            val drawW = bmpW * scale
            val drawH = bmpH * scale
            val left = footprintCx - drawW / 2f
            val top = footprintBottom - drawH
            return RectF(left, top, left + drawW, footprintBottom)
        }

        val half = maxDim / 2f
        return RectF(
            footprintCx - half,
            footprintBottom - maxDim,
            footprintCx + half,
            footprintBottom,
        )
    }

    /**
     * HP bar drawn just above [spriteRect], matching its width so
     * it scales with the enemy silhouette. May extend slightly
     * above the tile when [spriteDisplayScale] > 1.
     */
    private fun drawEnemyHealthBar(
        canvas: Canvas,
        spriteRect: RectF,
        hp: Int,
        maxHp: Int,
    ) {
        if (maxHp <= 0) return
        val barH = dp(Companion.ENEMY_HP_BAR_HEIGHT_DP)
        val gap = dp(Companion.ENEMY_HP_BAR_GAP_DP)
        val barW = (spriteRect.width() * ENEMY_HP_BAR_WIDTH_FRACTION)
            .coerceAtLeast(dp(12f))
        val cx = (spriteRect.left + spriteRect.right) / 2f
        val left = cx - barW / 2f
        val top = spriteRect.top - gap - barH
        val rect = RectF(left, top, left + barW, top + barH)
        val radius = dp(1f)
        val ratio = (hp.toFloat() / maxHp.toFloat()).coerceIn(0f, 1f)

        canvas.drawRoundRect(rect, radius, radius, enemyHpBarTrackPaint)
        if (ratio > 0f) {
            val fill = RectF(rect.left, rect.top, rect.left + rect.width() * ratio, rect.bottom)
            canvas.drawRoundRect(fill, radius, radius, enemyHpBarFillPaint)
        }
        canvas.drawRoundRect(rect, radius, radius, enemyHpBarBorderPaint)
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

    private fun syncSelectionMarkerClock(selected: Enemy) {
        if (selectionMarkerTarget !== selected) {
            selectionMarkerTarget = selected
            selectionMarkerStartMs = SystemClock.uptimeMillis()
        }
    }

    private fun clearSelectionMarkerClock() {
        selectionMarkerTarget = null
        selectionMarkerStartMs = 0L
    }

    private fun selectionMarkerElapsedMs(): Long =
        (SystemClock.uptimeMillis() - selectionMarkerStartMs).coerceAtLeast(0L)

    /** True while the intro bounce + frame cycle should still play. */
    private fun isSelectionMarkerAnimating(): Boolean =
        selectionMarkerElapsedMs() < SELECTION_MARKER_ANIM_TOTAL_MS

    /**
     * Paints the "look here" arrow above [cell] using the
     * pre-loaded [selectionMarkerFrames]. The arrow points down,
     * so the marker is anchored so its bottom edge meets the
     * top of the target cell - the tip just kisses the goblin
     * underneath.
     *
     * Animation (one shot per enemy selection):
     *   - Cycles `loc1 -> loc1b -> loc2 -> loc2b` at
     *     [SELECTION_MARKER_FRAME_MS] (25% faster than the prior
     *     352ms cadence).
     *   - Bobs vertically [SELECTION_MARKER_BOUNCE_COUNT] times
     *     via a sine wave over [SELECTION_MARKER_ANIM_TOTAL_MS].
     *   - After that window elapses the marker is not drawn again
     *     until the player selects a different enemy.
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
        val elapsedMs = selectionMarkerElapsedMs()
        val frameIdx = (
            elapsedMs / SELECTION_MARKER_FRAME_MS
            ).toInt().mod(selectionMarkerFrames.size)
        val sprite = selectionMarkerFrames[frameIdx] ?: return

        val topLeftX = (cell.x - camCx) * cellPx + viewCx
        val topLeftY = (cell.y - camCy) * cellPx + viewCy
        val size = cellPx * SELECTION_MARKER_CELL_FRACTION
        val cx = topLeftX + cellPx / 2f
        val left = cx - size / 2f
        val right = cx + size / 2f
        val restingBottom = topLeftY
        val restingTop = restingBottom - size

        // Three full up-down bounces over the intro window; fade
        // out is implicit (we stop drawing entirely after the window).
        val bounceT = elapsedMs.toDouble() / SELECTION_MARKER_ANIM_TOTAL_MS.toDouble()
        val bounceOffset = sin(
            2.0 * PI * SELECTION_MARKER_BOUNCE_COUNT.toDouble() * bounceT,
        ).toFloat() * cellPx * SELECTION_MARKER_BOUNCE_AMP_FRACTION

        dstRect.set(left, restingTop - bounceOffset, right, restingBottom - bounceOffset)
        canvas.drawBitmap(sprite, selectionMarkerSrcRects[frameIdx], dstRect, drawBitmapPaint)
    }

    private fun drawPartyToken(
        canvas: Canvas,
        game: Game,
        cell: Cell,
        camCx: Float, camCy: Float,
        cellPx: Float,
        viewCx: Float, viewCy: Float,
    ) {
        // ----- Hop animation bookkeeping -----
        // Detect a cell change since the last draw. The very first
        // frame after attachGame has lastSeenPartyCell == null, in
        // which case we just snap (no animation) - same idea the
        // floor-change reset uses.
        val previousCell = lastSeenPartyCell
        if (previousCell == null) {
            lastSeenPartyCell = cell
        } else if (previousCell != cell) {
            partyStepFromCell = previousCell
            partyStepStartMs = SystemClock.uptimeMillis()
            lastSeenPartyCell = cell
        }

        // Hop duration is tuned per game mode:
        //   - Exploration: ~MOVE_STEP_SEC so consecutive auto-walk
        //     steps land just as the next one lifts off (no visible
        //     "snap to ground mid-air" glitch on multi-cell paths).
        //   - Combat: a touch longer so a one-shot disengage / free
        //     step reads as a deliberate reposition.
        val inCombat = game.mode == Game.Mode.COMBAT
        val hopDurationMs = if (inCombat) PARTY_HOP_DURATION_MS_COMBAT
            else PARTY_HOP_DURATION_MS_EXPLORATION
        val liftFraction = if (inCombat) PARTY_HOP_LIFT_FRACTION_COMBAT
            else PARTY_HOP_LIFT_FRACTION_EXPLORATION

        // Lift progress: 0 = piece on board, 1 = hop complete.
        // sin(pi * t) gives a clean up-and-down arc with peak at
        // t=0.5 - same easing the exit-mark "!" bounce already
        // uses below for visual consistency.
        val fromCell = partyStepFromCell
        val elapsedMs = SystemClock.uptimeMillis() - partyStepStartMs
        val hopRaw = if (fromCell != null && hopDurationMs > 0L) {
            (elapsedMs.toFloat() / hopDurationMs.toFloat()).coerceIn(0f, 1f)
        } else 1f
        val isAirborne = fromCell != null && hopRaw < 1f
        if (!isAirborne) partyStepFromCell = null

        // Interpolated cell-space position. When the hop is done
        // (or never started), this collapses to the target cell.
        val origin = fromCell ?: cell
        val visualCellX = origin.x + (cell.x - origin.x) * hopRaw
        val visualCellY = origin.y + (cell.y - origin.y) * hopRaw
        val visualScreenX = (visualCellX - camCx) * cellPx + viewCx
        val visualScreenY = (visualCellY - camCy) * cellPx + viewCy

        // Resting baseline Y (where the icon's base sits when
        // not airborne). Cached up-front so the shadow and the
        // icon-anchor math agree on the same ground line.
        val baseOffsetPx = cellPx * PARTY_ICON_BASE_OFFSET_FRACTION
        val restingBaseY = visualScreenY + cellPx - baseOffsetPx

        // ----- Shadow under the airborne piece -----
        // Drawn BEFORE the icon so the icon overlaps it. Width and
        // alpha shrink with lift to mimic an overhead-ish light:
        // higher piece -> smaller, softer shadow underneath. We
        // skip drawing entirely once the piece has settled so a
        // stationary party doesn't sport a permanent black smudge.
        if (isAirborne) {
            val lift01 = sin(PI * hopRaw.toDouble()).toFloat().coerceIn(0f, 1f)
            val shadowScale = 1f - PARTY_SHADOW_SHRINK_FRACTION * lift01
            val shadowW = cellPx * PARTY_SHADOW_W_FRACTION * shadowScale
            val shadowH = cellPx * PARTY_SHADOW_H_FRACTION * shadowScale
            val shadowCx = visualScreenX + cellPx / 2f
            // Shadow CENTER sits right under the piece's resting
            // base - NOT at the literal tile bottom. That keeps
            // the shadow visually attached to the piece's feet
            // even though the piece itself now floats a little
            // above the tile's south edge for composition.
            val shadowCy = restingBaseY
            val alpha = (PARTY_SHADOW_BASE_ALPHA - (PARTY_SHADOW_BASE_ALPHA - PARTY_SHADOW_PEAK_ALPHA) * lift01)
                .toInt()
                .coerceIn(0, 255)
            partyShadowPaint.alpha = alpha
            dstRect.set(
                shadowCx - shadowW / 2f,
                shadowCy - shadowH / 2f,
                shadowCx + shadowW / 2f,
                shadowCy + shadowH / 2f,
            )
            canvas.drawOval(dstRect, partyShadowPaint)
        }

        // ----- Party token sprite -----
        // Icon is rendered slightly taller than a cell and
        // anchored to a baseline that sits PARTY_ICON_BASE_OFFSET_FRACTION
        // above the tile bottom (so the piece reads as standing
        // in the middle of the square, not glued to its south
        // edge). The lift offset is applied as a negative Y so
        // the icon arcs up and back down to that same baseline.
        val iconWidth = cellPx * PARTY_ICON_WIDTH_FRACTION
        val iconHeight = cellPx * PARTY_ICON_HEIGHT_FRACTION
        val liftPx = if (isAirborne) {
            sin(PI * hopRaw.toDouble()).toFloat() * cellPx * liftFraction
        } else 0f

        val footprintCx = visualScreenX + cellPx / 2f
        val iconLeft = footprintCx - iconWidth / 2f
        val iconRight = footprintCx + iconWidth / 2f
        val iconBottom = restingBaseY - liftPx
        val iconTop = iconBottom - iconHeight

        val sprite = partyIcon
        if (sprite != null) {
            dstRect.set(iconLeft, iconTop, iconRight, iconBottom)
            canvas.drawBitmap(sprite, partyIconSrc, dstRect, drawBitmapPaint)
        } else {
            // Fallback for the (unlikely) case where party_icon.png
            // failed to decode at startup; keeps the old procedural
            // ring so the party is at least visible. Centered on
            // the icon's bounding box so the lift+shadow effect
            // still applies to the fallback.
            val sx = footprintCx
            val sy = iconTop + iconHeight / 2f
            val outer = iconWidth * 0.38f
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

        // -------- Party token sizing & hop animation tunables --------

        /**
         * Party-token render height as a multiple of a cell's
         * size. Width still matches the cell exactly (so the
         * silhouette doesn't drift sideways off its tile) but the
         * icon is rendered taller than a cell (see
         * [PARTY_ICON_HEIGHT_FRACTION]), anchored to the cell's
         * BOTTOM edge. Width uses [PARTY_ICON_WIDTH_FRACTION] and
         * is centered on the tile so a wider silhouette doesn't
         * drift off the cell. Both fractions were bumped 10% from
         * the prior 1.0 / 1.18 baselines.
         */
        private const val PARTY_ICON_WIDTH_FRACTION: Float = 1.1f
        private const val PARTY_ICON_HEIGHT_FRACTION: Float = 1.298f

        /**
         * Vertical anchor offset for the party icon's base,
         * measured as a fraction of cellPx UPWARD from the
         * cell's bottom edge. At 0f the base touches the tile's
         * south line; at 0.18f the base sits 18% above the tile
         * bottom (visually closer to the tile's center, which
         * reads as "piece standing in the middle of the square"
         * rather than "piece glued to the back wall"). The
         * airborne shadow's ground line is derived from this
         * same offset so the shadow always lands right beneath
         * the resting base, not at the literal tile floor.
         */
        private const val PARTY_ICON_BASE_OFFSET_FRACTION: Float = 0.18f

        /**
         * Duration of one lift-glide-land hop while the party is
         * auto-walking outside of combat. Matched to
         * [Game.MOVE_STEP_SEC] (120ms) so a multi-cell path
         * lands each step exactly as the next step lifts off -
         * no visible "snap to ground mid-air" between cells.
         */
        private const val PARTY_HOP_DURATION_MS_EXPLORATION: Long = 120L

        /**
         * Duration of the hop for a single-shot in-combat move
         * (free step, disengage success, etc.). Longer than the
         * exploration cadence because there's no follow-up step
         * to chain into - the player has time to read the lift
         * as a deliberate reposition.
         */
        private const val PARTY_HOP_DURATION_MS_COMBAT: Long = 230L

        /**
         * Peak hop height as a fraction of cellPx, exploration.
         * Bumped to 35% so the chess-piece arc reads even at the
         * fast 120ms auto-walk cadence; on a long multi-cell
         * path consecutive hops still chain cleanly because the
         * land of step N coincides with the lift of step N+1.
         */
        private const val PARTY_HOP_LIFT_FRACTION_EXPLORATION: Float = 0.35f

        /**
         * Peak hop height as a fraction of cellPx, combat. The
         * 230ms single-shot timing gives the arc room to breathe,
         * so we double down (60%) to make a deliberate combat
         * reposition feel like a real "pick up + place" gesture.
         */
        private const val PARTY_HOP_LIFT_FRACTION_COMBAT: Float = 0.60f

        /**
         * Width / height of the airborne-piece drop shadow, as a
         * fraction of cellPx. The 4:1 width-to-height ratio reads
         * as "ellipse on a flat floor" rather than "circle on a
         * pedestal".
         */
        private const val PARTY_SHADOW_W_FRACTION: Float = 0.52f
        private const val PARTY_SHADOW_H_FRACTION: Float = 0.14f

        /**
         * Shadow alpha at ground (t=0 or t=1) and at peak lift
         * (t=0.5). Peak alpha is intentionally LOWER so the
         * shadow softens as the piece rises, reinforcing the
         * "off the board" read. Range 0..255.
         */
        private const val PARTY_SHADOW_BASE_ALPHA: Float = 140f
        private const val PARTY_SHADOW_PEAK_ALPHA: Float = 70f

        /**
         * How much the shadow ellipse shrinks at peak lift,
         * fraction of base size. 18% shrink reads as "object is
         * higher" without making the shadow vanish.
         */
        private const val PARTY_SHADOW_SHRINK_FRACTION: Float = 0.18f

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
         * advancing. 264ms is 25% faster than the prior 352ms
         * cadence (352 * 0.75).
         */
        private const val SELECTION_MARKER_FRAME_MS: Long = 264L

        /**
         * How many full vertical bounces play during the intro,
         * then the marker disappears for the rest of that selection.
         */
        private const val SELECTION_MARKER_BOUNCE_COUNT: Int = 3

        /**
         * Total wall-clock window for the intro (frame cycle +
         * bounces). Three bounces at [SELECTION_MARKER_BOUNCE_CYCLE_MS]
         * each = 660ms, which also fits ~2.5 sprite cycles at the
         * faster frame cadence.
         */
        private const val SELECTION_MARKER_BOUNCE_CYCLE_MS: Long = 220L
        private const val SELECTION_MARKER_ANIM_TOTAL_MS: Long =
            SELECTION_MARKER_BOUNCE_CYCLE_MS * SELECTION_MARKER_BOUNCE_COUNT

        /**
         * Peak vertical bounce as a fraction of cellPx. Applied as
         * a negative Y offset so the arrow lifts above the enemy.
         */
        private const val SELECTION_MARKER_BOUNCE_AMP_FRACTION: Float = 0.14f

        /**
         * Side length of the selected-enemy marker expressed as a
         * fraction of a single dungeon cell. 0.425 = 15% smaller
         * than the previous 0.5; keeps the arrow readable on
         * phone screens while letting adjacent tiles breathe.
         */
        private const val SELECTION_MARKER_CELL_FRACTION: Float = 0.425f

        /** Enemy overhead HP bar geometry (in dp). */
        private const val ENEMY_HP_BAR_HEIGHT_DP: Float = 5.5f
        private const val ENEMY_HP_BAR_GAP_DP: Float = 3f

        /**
         * HP bar width as a fraction of the sprite width. 0.81 keeps
         * the bar noticeably narrower than the silhouette so it reads
         * as an overlay rather than a second sprite edge.
         */
        private const val ENEMY_HP_BAR_WIDTH_FRACTION: Float = 0.81f

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
