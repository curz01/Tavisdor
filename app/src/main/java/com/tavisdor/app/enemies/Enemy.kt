package com.tavisdor.app.enemies

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.party.Hero
import kotlin.random.Random

/**
 * One enemy instance on the dungeon grid. Holds the volatile state
 * a fight mutates (HP, MP, position) and forwards to its
 * [EnemyTemplate] for everything authored.
 *
 * Derived attributes (Max HP / Max MP / Dodge) intentionally share
 * the same formulas as [Hero]:
 *   - Max HP = baseMaxHp + STR * STR_HP_PER_POINT
 *   - Max MP = baseMaxMp + INT * INT_MP_PER_POINT
 *   - Dodge  = DEX * DEX_DODGE_PCT_PER_POINT (capped at 90%)
 * Keeping enemies on the same math as the party means combat
 * resolution can treat both sides uniformly when it lands.
 *
 * Instances are normally created via [spawnAt], which fills HP / MP
 * to their derived maxes. The data class itself is freely copyable
 * so combat can advance state functionally (`enemy.copy(hp = ...)`).
 */
data class Enemy(
    val template: EnemyTemplate,
    /** Current HP. Mutated by combat; 0 = defeated. */
    var hp: Int,
    /** Current MP. Mutated by combat when the enemy casts. */
    var mp: Int,
    /**
     * Dungeon-grid position. Mutated only through
     * [com.tavisdor.app.dungeon.Floor.moveEnemy] so the floor's
     * `_enemyByCell` / `_enemyByPlacement` indexes stay in sync
     * with the new cell; assigning here directly would orphan the
     * index entries.
     */
    var cell: Cell,
    /**
     * Floor-lock ids this enemy drops on defeat. Assigned at floor gen
     * for enemies reachable without crossing a locked door.
     */
    val floorKeyLockIds: MutableList<String> = mutableListOf(),
) {
    // ----- Forwarded authoring fields (convenience accessors) -----

    val id: String get() = template.id
    val name: String get() = template.name
    val level: Int get() = template.level
    val element: Element get() = template.element
    val movementSquares: Int get() = template.movementSquares
    val weaponType get() = template.weaponType
    val strength: Int get() = template.strength
    val dexterity: Int get() = template.dexterity
    val intelligence: Int get() = template.intelligence
    val armorClass: Int get() = template.armorClass

    // ----- Derived pools (mirror Hero's formulas) -----

    val maxHp: Int get() = template.baseMaxHp + strength * Hero.STR_HP_PER_POINT
    val maxMp: Int get() = template.baseMaxMp + intelligence * Hero.INT_MP_PER_POINT

    /** Dodge chance percent. Capped at 90% so 100% evaders aren't possible. */
    val dodgeChancePct: Int
        get() = (dexterity * Hero.DEX_DODGE_PCT_PER_POINT).coerceAtMost(90)

    // ----- Combat runtime helpers -----

    /** True iff this enemy still has positive HP. */
    val isAlive: Boolean get() = hp > 0

    /**
     * Subtracts [amount] from [hp], floored at 0. No-op when
     * [amount] is non-positive. Returns the actual damage applied.
     */
    fun takeDamage(amount: Int): Int {
        if (amount <= 0) return 0
        val before = hp
        hp = (hp - amount).coerceAtLeast(0)
        return before - hp
    }

    /** Spends [cost] mana, floored at 0. Returns mana actually spent. */
    fun spendMana(cost: Int): Int {
        if (cost <= 0) return 0
        val before = mp
        mp = (mp - cost).coerceAtLeast(0)
        return before - mp
    }

    // ----- Per-kill rewards -----

    /**
     * Rolls a gold drop in `[goldMin, goldMax]` against [rng]. Pure;
     * call once per kill and credit the result to the party purse.
     */
    fun rollGold(rng: Random): Int =
        if (template.goldMin == template.goldMax) template.goldMin
        else rng.nextInt(template.goldMin, template.goldMax + 1)

    companion object {
        /**
         * Builds a fresh enemy instance from [template] at [cell],
         * with HP / MP filled to their derived maxes. Use this for
         * encounter spawning; the raw constructor is for load / copy
         * / test code that wants to override individual fields.
         */
        fun spawnAt(template: EnemyTemplate, cell: Cell): Enemy {
            val proto = Enemy(template = template, hp = 1, mp = 0, cell = cell)
            return proto.copy(hp = proto.maxHp, mp = proto.maxMp)
        }
    }
}
