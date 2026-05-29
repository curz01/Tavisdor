package com.tavisdor.app.dungeon

/**
 * Runtime state for a single door cell on a [Floor]. Door positions come
 * from green pixels in the source room PNGs; locked-ness is rolled per
 * instance in [Floor.commitTemplate].
 *
 * [axis] describes how the connected rooms align: north–south passages use
 * the NS door sprites; east–west passages use the EW sprites. Inferred from
 * floor geometry when the door is first registered.
 *
 * [lockId] pairs this door with a [com.tavisdor.app.items.FloorKey] dropped
 * on the same dungeon depth. [bruteDamaged] blocks Thief lock pick after a
 * successful STR force; [strForceAttempted] allows only one STR try per lock.
 *
 * [visuallyOpen] is the only flag that selects the opened door sprite.
 * Unlocking ([locked] = false) does not change the art — the door stays
 * closed until the party walks through or a template sets [visuallyOpen].
 */
class Door(
    var locked: Boolean,
    val axis: DoorAxis,
    val lockId: String,
    var bruteDamaged: Boolean = false,
    var strForceAttempted: Boolean = false,
    var visuallyOpen: Boolean = false,
)

/** Which axis the door's connecting passage runs along. */
enum class DoorAxis {
    /** Rooms connect north and south of the door. */
    NS,
    /** Rooms connect east and west of the door. */
    EW,
}

/**
 * Picks NS when walkable floor extends further north/south than east/west
 * from [doorCell]; otherwise EW.
 */
fun inferDoorAxis(doorCell: Cell, floorCells: Set<Cell>): DoorAxis {
    fun walkable(c: Cell): Boolean = c in floorCells

    fun rayLength(dx: Int, dy: Int): Int {
        var len = 0
        var x = doorCell.x + dx
        var y = doorCell.y + dy
        while (walkable(Cell(x, y))) {
            len++
            x += dx
            y += dy
        }
        return len
    }

    val nsScore = rayLength(0, -1) + rayLength(0, 1)
    val ewScore = rayLength(1, 0) + rayLength(-1, 0)
    return if (nsScore >= ewScore) DoorAxis.NS else DoorAxis.EW
}
