package com.tavisdor.app.combat

import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.skills.SkillCatalog

/**
 * Damage-over-time (DoT) effects for an active [Combat] encounter.
 *
 * **Timing rule (all DoT, present and future):** damage and duration
 * ticks resolve at the **start** of the affected unit's slot in
 * [CombatRound] initiative order — when [CombatController.queueCurrentActor]
 * hands the turn to that hero or enemy, **before** they act. DoT does
 * not tick at round boundaries or mid-swing.
 *
 * Add new DoT types here (burn, bleed, hero poison, etc.) and register
 * their tick in [onActorTurnStart].
 */
class TurnOrderDotEffects(
    enemyCount: Int,
) {
    /**
     * Remaining poison ticks per enemy list index. One tick fires at the
     * start of that enemy's next initiative turns.
     */
    private val enemyPoisonTurnsRemaining: IntArray = IntArray(enemyCount)

    /**
     * Runs every DoT registered for this actor at turn-start. Returns
     * true when the actor died during ticking (caller should end the turn).
     */
    fun onActorTurnStart(
        entry: InitiativeEntry,
        enemy: Enemy?,
        log: CombatLog,
        onEnemyKo: (Enemy) -> Unit,
    ): Boolean {
        return when (entry.kind) {
            InitiativeEntry.Kind.HERO -> false
            InitiativeEntry.Kind.ENEMY -> {
                val e = enemy ?: return false
                tickEnemyPoisonAtTurnStart(entry.index, e, log, onEnemyKo)
            }
        }
    }

    fun applyPoisonArrowToEnemy(targetIdx: Int, target: Enemy, log: CombatLog) {
        if (targetIdx !in enemyPoisonTurnsRemaining.indices) return
        enemyPoisonTurnsRemaining[targetIdx] = SkillCatalog.ARCHER_POISON_ARROW_DURATION_TURNS
        log.append(
            CombatLogEntry.Info(
                "${target.name} is poisoned — " +
                    "${SkillCatalog.ARCHER_POISON_ARROW_DAMAGE_PER_TURN} damage per turn for " +
                    "${SkillCatalog.ARCHER_POISON_ARROW_DURATION_TURNS} turns.",
            ),
        )
    }

    fun clearEnemy(enemyIdx: Int) {
        if (enemyIdx in enemyPoisonTurnsRemaining.indices) {
            enemyPoisonTurnsRemaining[enemyIdx] = 0
        }
    }

    private fun tickEnemyPoisonAtTurnStart(
        enemyIdx: Int,
        enemy: Enemy,
        log: CombatLog,
        onEnemyKo: (Enemy) -> Unit,
    ): Boolean {
        if (enemyIdx !in enemyPoisonTurnsRemaining.indices) return false
        val turnsBefore = enemyPoisonTurnsRemaining[enemyIdx]
        if (turnsBefore <= 0 || !enemy.isAlive) return false

        val damage = SkillCatalog.ARCHER_POISON_ARROW_DAMAGE_PER_TURN
        val dealt = enemy.takeDamage(damage)
        enemyPoisonTurnsRemaining[enemyIdx] = turnsBefore - 1
        val turnsLeft = enemyPoisonTurnsRemaining[enemyIdx]

        val tickText = buildString {
            append(enemy.name)
            append(" suffers ")
            append(if (dealt > 0) dealt else damage)
            append(" poison damage")
            if (turnsLeft > 0) {
                append(" (")
                append(turnsLeft)
                append(if (turnsLeft == 1) " turn" else " turns")
                append(" remaining)")
            }
            append('.')
        }
        log.append(CombatLogEntry.Info(tickText))

        if (!enemy.isAlive) {
            clearEnemy(enemyIdx)
            onEnemyKo(enemy)
            return true
        }

        if (turnsLeft <= 0) {
            clearEnemy(enemyIdx)
            log.append(
                CombatLogEntry.Info("Poison wears off — ${enemy.name} recovers."),
            )
        }
        return false
    }
}
