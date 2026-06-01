package com.tavisdor.app.enemies

/**
 * Combat role for [EnemyTemplate]s. Mirrors hero class rules where it
 * applies ([com.tavisdor.app.items.WeaponClassRules]); [BEAST] is
 * enemy-only (natural attacks, no spear/bow class perks).
 */
enum class EnemyCombatClass {
    FIGHTER,
    ARCHER,
    MAGE,
    THIEF,
    BEAST,
}
