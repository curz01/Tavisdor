package com.tavisdor.app.render

import com.tavisdor.app.combat.HealResolver
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.enemies.Element
import com.tavisdor.app.items.WeaponType
import com.tavisdor.app.skills.Skill

/**
 * Describes one weapon / spell attack visual to play between an
 * [attackerCell] and [defenderCell]. Works for heroes and enemies
 * alike - only cells and weapon context matter.
 */
/**
 * Describes how many arrows to animate and whether they fly in
 * parallel (Double Shot) or as separate bow cycles (Rapid Fire).
 */
data class BowVolleyPlan(
    val volleys: List<BowVolley>,
    /** Projectile sprite: `arrow` or `fire_arrow`. */
    val arrowAsset: String,
)

sealed class BowVolley {
    /** One bow draw; [arrowCount] arrows fly side-by-side (Double Shot). */
    data class Parallel(val arrowCount: Int) : BowVolley()

    /** Full draw + flight per arrow, played back-to-back (Rapid Fire). */
    data class Sequential(val arrowCount: Int) : BowVolley()
}

data class WeaponFxRequest(
    val attackerCell: Cell,
    val defenderCell: Cell,
    val kind: WeaponFxKind,
    /** Equipped weapon on the attacker; drives arc sprite choice. */
    val weaponType: WeaponType? = null,
    /** Optional duration override for this specific playback. */
    val durationMsOverride: Long? = null,
    /** When set, bow / fire-arrow kinds play multiple volleys instead of one shot. */
    val bowVolleyPlan: BowVolleyPlan? = null,
    /**
     * Animated overlay frames drawn at the staff tip during
     * [WeaponFxKind.STAFF_SPELL_RISE] (e.g. `heali_1`, `earthi_2`).
     */
    val spellFlowFrames: List<String> = emptyList(),
    /** When true, pivot the cast on the party token center instead of the cell center. */
    val castFromPartyIcon: Boolean = false,
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
    /** Staff spell cast - upright slow rise from the party icon + flow at the tip. */
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
     * Spell visuals: archer Fire Arrow uses [fire_arrow]; mage heals
     * and elemental spells use [STAFF_SPELL_RISE].
     */
    fun kindForSpell(weaponType: WeaponType?, isFireArrowSkill: Boolean): WeaponFxKind {
        if (isFireArrowSkill) return WeaponFxKind.FIRE_PROJECTILE
        return WeaponFxKind.STAFF_SPELL_RISE
    }

    /** Tip overlay cycle for [WeaponFxKind.STAFF_SPELL_RISE], keyed by spell. */
    fun spellFlowFrames(skill: Skill): List<String> {
        if (HealResolver.isHeal(skill)) {
            return listOf("heali_1", "heali_2")
        }
        return when (skill.element) {
            Element.FIRE -> listOf("fireball1", "fireball2")
            Element.EARTH -> listOf("earthi_1", "earthi_2", "earthi_3")
            Element.WATER -> listOf("ice_arrow")
            Element.AIR -> listOf("thunder_arrow")
            else -> emptyList()
        }
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
