package com.tavisdor.app.locks

/**
 * Authored lock difficulties by dungeon depth (design table: locked doors
 * and chests). [dexPickThreshold] is the target for Thief lock pick
 * (DEX + 1d3); [strForceThreshold] is for forcing doors (STR + 1d6).
 */
object LockLevel {

    fun dexPickThreshold(dungeonDepth: Int): Int = when {
        dungeonDepth <= 9 -> 5
        dungeonDepth <= 19 -> 10
        dungeonDepth <= 29 -> 15
        else -> 20
    }

    fun strForceThreshold(dungeonDepth: Int): Int = when {
        dungeonDepth <= 9 -> 5
        dungeonDepth <= 19 -> 15
        dungeonDepth <= 29 -> 25
        else -> 50
    }
}
