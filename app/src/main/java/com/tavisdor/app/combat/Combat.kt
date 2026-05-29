package com.tavisdor.app.combat

import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.party.Party
import kotlin.random.Random

/**
 * One active turn-based encounter. Created when the party enters a
 * populated room and discarded when enemies or the party are wiped
 * out. Holds the combatants, the dexterity-ordered initiative list,
 * and the [round] state machine driving the turn-order UI.
 *
 * Action resolution (attack / spell / move math) lives in
 * [CombatMath] - this class is the orchestration shell that the
 * Game loop calls into.
 */
class Combat(
    val party: Party,
    val enemies: MutableList<Enemy>,
    rng: Random = Random.Default,
    /**
     * When true (camp ambush), every enemy acts before any hero each
     * round, regardless of DEX. Enemies and heroes are still ordered
     * among themselves by DEX + tiebreak d6.
     */
    enemiesActFirst: Boolean = false,
) {
    /**
     * Stable initiative list for the entire encounter. DEX ties
     * are resolved at construction via [Initiative.build]'s d6
     * tournament so [currentTurnIndex] can iterate predictably.
     */
    val initiative: List<InitiativeEntry> = Initiative.build(
        heroDex = party.heroes.map { it.dexterity },
        enemyDex = enemies.map { it.dexterity },
        rng = rng,
        enemiesActFirst = enemiesActFirst,
    )

    /**
     * Round-level state: who's acting, which portrait is mid
     * slide-off, what round we're on. Owned here so the UI can
     * read it through [Combat]; mutations route through this
     * object's methods.
     */
    val round: CombatRound = CombatRound(initiative)

    /**
     * Per-encounter aggro engine. Each enemy tracks a 1..5 hate
     * value against every hero; the AI uses it to pick targets and
     * the UI surfaces it as `hate1..hate5` icons on each hero
     * panel when the player has an enemy selected.
     *
     * Hate resets to default whenever a fresh [Combat] is
     * constructed (i.e. every time a new encounter starts), which
     * matches the design brief: "Start of battle unless specified,
     * all heroes are treated with hate 1."
     */
    val hate: HateTracker = HateTracker(
        enemyCount = enemies.size,
        partySize = party.heroes.size,
    )

    /**
     * Convenience pointer to [CombatRound.actingIndex]. Kept on
     * [Combat] for back-compat with any caller that reached for
     * `currentTurnIndex` directly.
     */
    val currentTurnIndex: Int
        get() = round.actingIndex

    fun isOver(): Boolean = enemies.all { it.hp <= 0 } || party.heroes.all { it.hp <= 0 }
}
