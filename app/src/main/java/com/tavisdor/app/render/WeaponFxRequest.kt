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
    /** Projectile sprite: `arrow`, `fire_arrow`, `poison_arrow`, or `ice_arrow`. */
    val arrowAsset: String,
)

sealed class BowVolley {
    /** One bow draw; [arrowCount] arrows fly side-by-side (Double Shot). */
    data class Parallel(val arrowCount: Int) : BowVolley()

    /**
     * Full draw + flight per arrow, played back-to-back (Rapid Fire).
     * [shotDurationMultipliers] shortens later shots (e.g. 1.0, 0.55, 0.35).
     */
    data class Sequential(
        val arrowCount: Int,
        val shotDurationMultipliers: List<Float>? = null,
    ) : BowVolley() {

        fun shotDurationMs(shotIndex: Int, baseShotMs: Long): Long {
            val mult = shotDurationMultipliers?.getOrNull(shotIndex) ?: 1f
            return (baseShotMs * mult).toLong().coerceAtLeast(1L)
        }

        fun totalDurationMs(baseShotMs: Long): Long {
            var sum = 0L
            for (i in 0 until arrowCount) {
                sum += shotDurationMs(i, baseShotMs)
            }
            return sum.coerceAtLeast(1L)
        }
    }
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
     * One callback per arrow; fired when that arrow's flight reaches the
     * defender so each shot rolls its own dodge / damage check.
     */
    val onBowShotImpact: List<() -> Unit>? = null,
    /**
     * Animated overlay frames drawn at the staff tip during
     * [WeaponFxKind.STAFF_SPELL_RISE] (e.g. `fireball1`, `earthi_2`).
     */
    val spellFlowFrames: List<String> = emptyList(),
    /**
     * When non-empty, tip frames follow this exact sequence (utility
     * casts). Takes precedence over [spellFlowFrames].
     */
    val flowFrameSequence: List<String> = emptyList(),
    /** Milliseconds per entry in [flowFrameSequence]. */
    val flowStepMs: Long = 130L,
    /** When false, only flow frames draw (utility camp / rest / etc.). */
    val showStaffDuringCast: Boolean = true,
    /** Scales tip overlay height for [flowFrameSequence] / [spellFlowFrames]. */
    val flowHeightScale: Float = 1f,
    /** Intro path for utility overlays; null for normal spell casts. */
    val utilityMotion: UtilityCastMotion? = null,
    /** Camp slide destination (adjacent tile center). */
    val utilityFocusCell: Cell? = null,
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
    /** Thief Double Strike: doubls1 then doubls2 spear-style thrusts. */
    DOUBLE_STRIKE_THRUST,
    /** Bow draw cycle + arrow on string, then arrow flight. */
    BOW_SHOT,
    /** Fire spell projectile (fire_arrow sprite). */
    FIRE_PROJECTILE,
    /** Fighter Charge: hold sword pointed at defender (no swing arc). */
    CHARGE_SWORD_HOLD,
    /**
     * Archer Feint Death: [scythe.png] rises on the staff spell path,
     * holds at the peak, then rotates 90° counter-clockwise.
     */
    FEINT_DEATH_RISE,
}

object WeaponFxCatalog {

    /**
     * Picks the animation for a physical attack using [weaponType].
     * Returns null when there is no weapon art (bare fists).
     */
    fun kindForWeaponAttack(weaponType: WeaponType?): WeaponFxKind? = when (weaponType) {
        null -> null
        WeaponType.BITE -> null
        WeaponType.SPEAR -> WeaponFxKind.SPEAR_THRUST
        WeaponType.STAFF -> WeaponFxKind.STAFF_MELEE_ARC
        WeaponType.DAGGER -> WeaponFxKind.DAGGER_COMBO
        WeaponType.BOW -> WeaponFxKind.BOW_SHOT
        WeaponType.MACE, WeaponType.HAMMER, WeaponType.SWORD, WeaponType.AXE ->
            WeaponFxKind.MELEE_ARC
    }

    /** Spell visuals: mage heals and elemental spells use [STAFF_SPELL_RISE]. */
    fun kindForSpell(weaponType: WeaponType?): WeaponFxKind = WeaponFxKind.STAFF_SPELL_RISE

    /** Tip overlay cycle for [WeaponFxKind.STAFF_SPELL_RISE], keyed by spell. */
    fun spellFlowFrames(skill: Skill): List<String> {
        if (HealResolver.isHeal(skill)) return emptyList()
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
        WeaponFxKind.DOUBLE_STRIKE_THRUST -> phase ?: "doubls1"
        WeaponFxKind.BOW_SHOT -> phase ?: "bow1"
        WeaponFxKind.FIRE_PROJECTILE -> "fire_arrow"
        WeaponFxKind.CHARGE_SWORD_HOLD -> "sword"
        WeaponFxKind.FEINT_DEATH_RISE -> "scythe"
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
