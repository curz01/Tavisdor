package com.tavisdor.app.render

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.items.WeaponType

/**
 * Describes one weapon / spell attack visual to play between an
 * [attackerCell] and [defenderCell]. Works for heroes and enemies
 * alike - only cells and weapon context matter.
 */
data class WeaponFxRequest(
    val attackerCell: Cell,
    val defenderCell: Cell,
    val kind: WeaponFxKind,
    /** Equipped weapon on the attacker; drives arc sprite choice. */
    val weaponType: WeaponType? = null,
    /** Optional duration override for this specific playback. */
    val durationMsOverride: Long? = null,
)

/**
 * Animation recipe selected from the attacker's equipped weapon
 * (and whether the strike is a spell cast).
 */
enum class WeaponFxKind {
    /** Mace, hammer, sword, axe - 180° arc, pivot at sprite bottom center. */
    MELEE_ARC,
    /** Spear - straight thrust from attacker toward defender. */
    SPEAR_THRUST,
    /** Staff swung as a melee weapon - same arc as [MELEE_ARC]. */
    STAFF_MELEE_ARC,
    /** Staff spell cast - slow rise with a soft glow pulse. */
    STAFF_SPELL_RISE,
    /** Dagger two-hit: dagger_r thrust, then dagger_l. */
    DAGGER_COMBO,
    /** Bow draw cycle + arrow on string, then arrow flight. */
    BOW_SHOT,
    /** Fire spell projectile (fire_arrow sprite). */
    FIRE_PROJECTILE,
    /** Fighter Charge: hold sword pointed at defender (no swing arc). */
    CHARGE_SWORD_HOLD,
}

object WeaponFxCatalog {

    /**
     * Picks the animation for a physical attack using [weaponType].
     * Returns null when there is no weapon art (bare fists).
     */
    fun kindForWeaponAttack(weaponType: WeaponType?): WeaponFxKind? = when (weaponType) {
        null -> null
        WeaponType.SPEAR -> WeaponFxKind.SPEAR_THRUST
        WeaponType.STAFF -> WeaponFxKind.STAFF_MELEE_ARC
        WeaponType.DAGGER -> WeaponFxKind.DAGGER_COMBO
        WeaponType.BOW -> WeaponFxKind.BOW_SHOT
        WeaponType.MACE, WeaponType.HAMMER, WeaponType.SWORD, WeaponType.AXE ->
            WeaponFxKind.MELEE_ARC
    }

    /**
     * Spell visuals: only Fire Arrow uses [fire_arrow] for now.
     * Other spell skills reuse staff rise until their own assets land.
     */
    fun kindForSpell(weaponType: WeaponType?, isFireArrowSkill: Boolean): WeaponFxKind {
        if (isFireArrowSkill) return WeaponFxKind.FIRE_PROJECTILE
        if (weaponType == WeaponType.STAFF) return WeaponFxKind.STAFF_SPELL_RISE
        return WeaponFxKind.STAFF_SPELL_RISE
    }

    fun assetName(kind: WeaponFxKind, phase: String? = null): String = when (kind) {
        WeaponFxKind.MELEE_ARC -> phase ?: meleeArcAsset(null)
        WeaponFxKind.SPEAR_THRUST -> "spear"
        WeaponFxKind.STAFF_MELEE_ARC -> "staff"
        WeaponFxKind.STAFF_SPELL_RISE -> "staff"
        WeaponFxKind.DAGGER_COMBO -> phase ?: "dagger_r"
        WeaponFxKind.BOW_SHOT -> phase ?: "bow1"
        WeaponFxKind.FIRE_PROJECTILE -> "fire_arrow"
        WeaponFxKind.CHARGE_SWORD_HOLD -> "sword"
    }

    /** Sprite file for the 180° arc swing (weapon points up in art). */
    fun meleeArcAsset(weaponType: WeaponType?): String = when (weaponType) {
        WeaponType.MACE -> "mace"
        WeaponType.HAMMER -> "hammer"
        WeaponType.SWORD -> "sword"
        WeaponType.AXE -> "axe"
        WeaponType.STAFF -> "staff"
        else -> "sword"
    }

    /** Bow cycle frame names in order. */
    val BOW_FRAMES: List<String> = listOf("bow1", "bow2", "bow3")
}
