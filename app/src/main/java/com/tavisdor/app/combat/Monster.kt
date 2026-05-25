package com.tavisdor.app.combat

import com.tavisdor.app.dungeon.Cell

/**
 * One monster instance during a combat encounter. The cell field locates the
 * monster on the dungeon grid so the renderer can draw it in place; combat
 * itself uses the [Initiative] order, not positions.
 */
data class Monster(
    val type: String,
    var hp: Int,
    val maxHp: Int,
    val dexterity: Int,
    val cell: Cell,
)
