package com.tavisdor.app.combat

import com.tavisdor.app.party.Party

/**
 * One active turn-based encounter. Created when the party enters a populated
 * room and discarded when monsters or the party are wiped out. Holds the
 * combatants and the dexterity-ordered initiative list; action resolution
 * (attack / spell / item / flee, damage math, status effects) is TODO.
 */
class Combat(
    val party: Party,
    val monsters: MutableList<Monster>,
) {
    val initiative: List<InitiativeEntry> = Initiative.build(
        heroDex = party.heroes.map { it.dexterity },
        monsterDex = monsters.map { it.dexterity },
    )

    /** Index into [initiative] indicating whose turn it is right now. */
    var currentTurnIndex: Int = 0
        private set

    fun isOver(): Boolean = monsters.all { it.hp <= 0 } || party.heroes.all { it.hp <= 0 }

    /** Advance to the next combatant whose owner is still alive. Stub. */
    fun advanceTurn() {
        // TODO: skip dead heroes / monsters, loop the round, broadcast events for animation.
        currentTurnIndex = (currentTurnIndex + 1) % initiative.size
    }
}
