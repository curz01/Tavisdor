package com.tavisdor.app.render

import com.tavisdor.app.dungeon.Cell

/**
 * Host-owned party movement tween used by Fighter Charge so the party
 * visibly lunges multiple cells instead of teleporting.
 */
interface PartyLungeGateway {
    fun startLunge(from: Cell, to: Cell, durationMs: Long)
    val isLunging: Boolean
}

