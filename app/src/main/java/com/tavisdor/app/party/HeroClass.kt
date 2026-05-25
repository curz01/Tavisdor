package com.tavisdor.app.party

/**
 * The four available classes at character creation. Behaviour (stats, abilities,
 * combat actions) will be attached later - this enum is intentionally just an
 * identifier so the rest of the project can compile and refer to classes by
 * name without depending on gameplay numbers yet.
 */
enum class HeroClass { MAGE, THIEF, FIGHTER, ARCHER }
