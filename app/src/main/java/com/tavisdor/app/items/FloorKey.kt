package com.tavisdor.app.items

/**
 * Key loot tied to one locked door or chest on a specific dungeon floor.
 * Only opens the lock with the same [lockId] on [floorDepth]; can be kept
 * across sessions but is useless on other depths or other locks.
 */
data class FloorKey(
    val floorDepth: Int,
    val lockId: String,
) {
    fun displayName(): String = "Dungeon Key (Floor $floorDepth)"
}
