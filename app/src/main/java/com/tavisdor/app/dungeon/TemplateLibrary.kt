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
 * Filename conventions (checked in this order so `end_room_*` is NOT
 * misclassified as `end_*`):
 *   - `start_*.png`     -> entrance room (one per floor, always at origin).
 *   - `end_room_*.png`  -> hallway end-cap. Used by [FloorGenerator] to
 *                          seal any still-open connectors after the
 *                          room budget is met. NOT a room for budget
 *                          purposes.
 *   - `end_*.png`       -> stairs-down room. Exactly one per floor;
 *                          [FloorGenerator] reserves placement until
 *                          the very end so it lands at the deepest
 *                          open connector.
 *   - `sp_*.png`        -> "special" room. At most one per floor.
 *   - `boss*.png`       -> boss room. Even-depth floors only, at most
 *                          one per floor.
 *   - `hall_*.png`      -> hallway segment. Links rooms together;
 *                          does NOT count toward the room budget.
 *   - `room_*.png`      -> destination room. Counts toward budget.
 *   - `tile*.png`       -> legacy unprefixed fallback. Treated as
 *                          `room_*` so old assets still work while
 *                          filenames are migrated.
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
                        "${it.doors.size} door, ${it.staircases.size} down, ${it.stairsUp.size} up" +
                        (if (it.isBoss) ", BOSS" else "") +
                        (if (it.isStart) ", START" else "") +
                        (if (it.isEnd) ", END" else "") +
                        (if (it.isEndCap) ", ENDCAP" else "") +
                        (if (it.isSpecial) ", SP" else "") +
                        (if (it.isHall) ", HALL" else "") +
                        (if (it.isRoom) ", ROOM" else "") +
                        ")\n${it.toAsciiGrid()}",
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
                val rawStairUp = HashSet<Cell>()
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
                            r == 255 && g == 255 && b == 0 -> {
                                rawFloor += Cell(x, y); rawStairUp += Cell(x, y)
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
                // Order-sensitive classification: `end_room_*` MUST be
                // checked before `end_*` (both start with "end_"), and the
                // legacy `tile*` fallback only fires when none of the
                // explicit prefixes match.
                val isStart = id.startsWith("start_", ignoreCase = true)
                val isEndCap = id.startsWith("end_room_", ignoreCase = true)
                val isEnd = !isEndCap && id.startsWith("end_", ignoreCase = true)
                val isSpecial = id.startsWith("sp_", ignoreCase = true)
                val isBoss = id.startsWith("boss", ignoreCase = true)
                val isHall = id.startsWith("hall_", ignoreCase = true)
                val explicitRoom = id.startsWith("room_", ignoreCase = true)
                val isLegacyTile = id.startsWith("tile", ignoreCase = true) &&
                    !isStart && !isEnd && !isEndCap && !isSpecial &&
                    !isBoss && !isHall && !explicitRoom
                val isRoom = explicitRoom || isLegacyTile
                return RoomTemplate(
                    id = id,
                    width = maxX - minX + 1,
                    height = maxY - minY + 1,
                    floorCells = rebase(rawFloor),
                    connectors = rebase(rawConn),
                    doors = rebase(rawDoor),
                    staircases = rebase(rawStair),
                    stairsUp = rebase(rawStairUp),
                    isBoss = isBoss,
                    isStart = isStart,
                    isEnd = isEnd,
                    isSpecial = isSpecial,
                    isHall = isHall,
                    isRoom = isRoom,
                    isEndCap = isEndCap,
                )
            } finally {
                bmp.recycle()
            }
        }
    }
}
