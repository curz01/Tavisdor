package com.tavisdor.app.dungeon

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log

/**
 * Loads room templates from PNG files under `assets/dungeon/rooms/`.
 *
 * Color mapping per pixel (alpha ignored):
 *   - White (#FFFFFF) -> void, skipped.
 *   - Black (#000000) -> internal floor cell.
 *   - Red   (#FF0000) -> floor cell AND a connector point.
 *   - Green (#00FF00) -> floor cell AND a door (lock state rolled at runtime).
 *   - Blue  (#0000FF) -> floor cell AND a staircase down to the next floor.
 *   - Any other color -> skipped with a warning log so typos are caught early.
 *
 * Filename conventions:
 *   - `tile*.png`  -> normal room template (eligible on every floor).
 *   - `boss*.png`  -> boss room (eligible only on even-depth floors, max
 *                    one per floor; see [Floor.bossTemplateAllowed]).
 *
 * The hallway folder is currently unused (a unified pool keeps the generator
 * simple); leave it empty until/unless a rooms/hallways distinction is needed.
 */
class TemplateLibrary private constructor(
    val rooms: List<RoomTemplate>,
) {

    fun isEmpty(): Boolean = rooms.isEmpty()

    companion object {
        private const val TAG = "TemplateLibrary"
        private const val ROOMS_DIR = "dungeon/rooms"

        /** Reads every PNG under `assets/dungeon/rooms/`. Sorted by filename for deterministic ordering. */
        fun loadFromAssets(context: Context): TemplateLibrary {
            val assets = context.assets
            val names = (assets.list(ROOMS_DIR) ?: emptyArray())
                .filter { it.endsWith(".png", ignoreCase = true) }
                .sorted()
            val rooms = names.mapNotNull { fileName ->
                runCatching { decode(context, "$ROOMS_DIR/$fileName", fileName.removeSuffix(".png")) }
                    .onFailure { Log.w(TAG, "Skipping template $fileName: ${it.message}") }
                    .getOrNull()
            }
            Log.i(TAG, "Loaded ${rooms.size} room template(s) from $ROOMS_DIR.")
            rooms.forEach {
                Log.d(
                    TAG,
                    "Template ${it.id} (${it.width}x${it.height}, " +
                        "${it.floorCells.size} floor, ${it.connectors.size} conn, " +
                        "${it.doors.size} door, ${it.staircases.size} stair" +
                        (if (it.isBoss) ", BOSS" else "") + ")\n${it.toAsciiGrid()}",
                )
            }
            return TemplateLibrary(rooms)
        }

        private fun decode(context: Context, path: String, id: String): RoomTemplate {
            val bmp = context.assets.open(path).use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("BitmapFactory could not decode $path.")
            try {
                val rawFloor = HashSet<Cell>()
                val rawConn = HashSet<Cell>()
                val rawDoor = HashSet<Cell>()
                val rawStair = HashSet<Cell>()
                for (y in 0 until bmp.height) {
                    for (x in 0 until bmp.width) {
                        val px = bmp.getPixel(x, y)
                        val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
                        when {
                            r == 255 && g == 255 && b == 255 -> Unit // void
                            r == 0 && g == 0 && b == 0 -> {
                                rawFloor += Cell(x, y)
                            }
                            r == 255 && g == 0 && b == 0 -> {
                                rawFloor += Cell(x, y); rawConn += Cell(x, y)
                            }
                            r == 0 && g == 255 && b == 0 -> {
                                rawFloor += Cell(x, y); rawDoor += Cell(x, y)
                            }
                            r == 0 && g == 0 && b == 255 -> {
                                rawFloor += Cell(x, y); rawStair += Cell(x, y)
                            }
                            else -> Log.w(TAG, "Unknown color in $path at ($x,$y): rgb($r,$g,$b) - ignored.")
                        }
                    }
                }
                require(rawFloor.isNotEmpty()) { "$path has no floor pixels." }
                // Translate bbox to origin so the model has no whitespace padding from the authoring canvas.
                val minX = rawFloor.minOf { it.x }
                val minY = rawFloor.minOf { it.y }
                val maxX = rawFloor.maxOf { it.x }
                val maxY = rawFloor.maxOf { it.y }
                fun rebase(set: HashSet<Cell>): HashSet<Cell> =
                    set.mapTo(HashSet(set.size)) { Cell(it.x - minX, it.y - minY) }
                return RoomTemplate(
                    id = id,
                    width = maxX - minX + 1,
                    height = maxY - minY + 1,
                    floorCells = rebase(rawFloor),
                    connectors = rebase(rawConn),
                    doors = rebase(rawDoor),
                    staircases = rebase(rawStair),
                    isBoss = id.startsWith("boss", ignoreCase = true),
                )
            } finally {
                bmp.recycle()
            }
        }
    }
}
