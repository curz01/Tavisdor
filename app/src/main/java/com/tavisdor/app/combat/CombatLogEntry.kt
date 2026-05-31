package com.tavisdor.app.combat

/**
 * One row of the in-combat action log. The
 * [com.tavisdor.app.ui.CombatLogView] picks a renderer per variant
 * (white names + connectives, red attack verbs + damage numbers).
 *
 * Variants intentionally carry RAW display data (names + ints) so
 * the view can format them however it likes - no pre-baked spans
 * here, since the view owns color choices and the entry should
 * stay loggable to plain text for save / debug dumps.
 *
 * Author new variants here when the resolver produces a new
 * outcome class (e.g. a future Heal entry once heals exist).
 */
sealed class CombatLogEntry {

    /** Connecting melee hit. */
    data class MeleeHit(
        val attacker: String,
        val target: String,
        val damage: Int,
        /** Natural d10 of 10 - mark for slightly different phrasing. */
        val crit: Boolean = false,
        /** When set, names the skill (e.g. Poison Arrow) instead of a plain attack. */
        val skillName: String? = null,
    ) : CombatLogEntry()

    /**
     * Melee swing that connected but the defender's AC was high
     * enough to soak it. Phrased differently from a dodge.
     */
    data class MeleeNoDamage(
        val attacker: String,
        val target: String,
        val skillName: String? = null,
    ) : CombatLogEntry()

    /** Defender dodged the swing (DEX check beat the attacker). */
    data class MeleeMiss(
        val attacker: String,
        val target: String,
        /** e.g. "arrow 2" for Rapid Fire / Double Shot volleys. */
        val shotLabel: String? = null,
        val skillName: String? = null,
    ) : CombatLogEntry()

    /** Spell landed; [advantage]/[disadvantage] surface elemental tags. */
    data class SpellHit(
        val attacker: String,
        val spellName: String,
        val target: String,
        val damage: Int,
        val advantage: Boolean = false,
        val disadvantage: Boolean = false,
        /** Natural d10 of 10 on the resist check. */
        val crit: Boolean = false,
    ) : CombatLogEntry()

    /**
     * Extra elemental damage from an archer Fire / Ice Arrow after the
     * physical shot connects. Uses the same matchup tags as [SpellHit].
     */
    data class ElementalBonusHit(
        val attacker: String,
        val skillName: String,
        val target: String,
        val damage: Int,
        val advantage: Boolean = false,
        val disadvantage: Boolean = false,
    ) : CombatLogEntry()

    /** Spell was resisted (target's INT check beat the caster). */
    data class SpellResist(
        val attacker: String,
        val spellName: String,
        val target: String,
    ) : CombatLogEntry()

    /**
     * Heal spell landed. [amount] is the HP ACTUALLY restored after
     * clamping at [com.tavisdor.app.party.Hero.heal] (a full-HP
     * target reads as 0, which the renderer phrases as "no effect"
     * so the player can see the heal was wasted rather than skipped).
     */
    data class HealCast(
        val caster: String,
        val spellName: String,
        val target: String,
        val amount: Int,
    ) : CombatLogEntry()

    /** Actor was KO'd (HP hit 0). Goes red - it's an attack result. */
    data class Defeat(val name: String) : CombatLogEntry()

    /** Encounter resolved in the party's favor. */
    object Victory : CombatLogEntry()

    /**
     * Summarises the gold scooped up from every defeated enemy
     * on victory. Posted as a single line ("Looted N gold.")
     * rather than one entry per corpse so the log stays
     * scrollable on big mob fights.
     */
    data class GoldAwarded(val amount: Int) : CombatLogEntry()

    /**
     * Per-hero XP credit posted after a victory. [amount] is the
     * scaled value the hero actually banked (post party-INT
     * multiplier), so the log line matches what the hero detail
     * panel will show on its next open.
     */
    data class XpGained(
        val name: String,
        val amount: Int,
    ) : CombatLogEntry()

    /**
     * Announces that a hero crossed an XP threshold and gained a
     * level. One entry per level - if a hero leaps two levels in
     * a single fight (big XP haul + low starting level) two
     * `LevelUp` entries are posted, one per stride.
     */
    data class LevelUp(
        val name: String,
        val newLevel: Int,
    ) : CombatLogEntry()

    /**
     * Announces a fresh skill / spell the hero just unlocked at
     * their new level. Only posted when [SkillCatalog.unlockedAt]
     * returns non-null for the level - some levels are stat-only
     * with no new ability.
     */
    data class SkillUnlocked(
        val name: String,
        val skillName: String,
    ) : CombatLogEntry()

    /**
     * Combined "what changed on level-up" line. Lists each stat
     * (STR / DEX / INT) and pool maximum (Max HP / Max MP) that
     * moved, with its before -> after pair. Unchanged stats are
     * omitted so the line stays short. Empty `deltas` means the
     * controller skipped the entry entirely.
     */
    data class StatChange(
        val name: String,
        val deltas: List<StatDelta>,
    ) : CombatLogEntry() {

        /**
         * One stat's before / after pair. [label] is the display
         * label (e.g. `"STR"`, `"Max HP"`).
         */
        data class StatDelta(
            val label: String,
            val before: Int,
            val after: Int,
        )
    }

    /**
     * Party wipe - all heroes KO'd. Triggered by the controller
     * right before the respawn teleport.
     */
    object PartyWipe : CombatLogEntry()

    /**
     * Party successfully slipped past adjacent enemies. [heroName]
     * is the hero whose DEX+INT drove the check (the one with the
     * highest stat among living party members).
     */
    data class DisengageSuccess(val heroName: String) : CombatLogEntry()

    /**
     * Party tried to disengage but at least one check failed.
     * [blockedByName] is the first enemy whose check went against
     * the party - usually enough to convey the "why" without
     * dumping every roll.
     */
    data class DisengageFail(
        val heroName: String,
        val blockedByName: String,
    ) : CombatLogEntry()

    /**
     * Party is hemmed in by enemies on all four cardinal sides.
     * Per the design: disengage is impossible until at least one
     * neighbor opens up. Posted to inform, not to charge the turn.
     */
    object DisengageSurrounded : CombatLogEntry()

    /**
     * Party stepped one cell during combat without needing a
     * disengage check (no enemy was touching the party). Reads
     * neutrally - no attack color, no reward color.
     */
    object PartyMoved : CombatLogEntry()

    /**
     * Fighter's Charge skill closed a gap before swinging. The
     * follow-up melee hit / miss / no-damage line still posts
     * via the usual [MeleeHit] / [MeleeMiss] entries; this one
     * announces only the lunge so the player understands why
     * the party token jumped to a new cell. [distance] is the
     * number of cardinal cells the party traversed (1 or 2 for
     * Charge today; higher tiers may extend that later).
     */
    data class HeroCharged(
        val heroName: String,
        val targetName: String,
        val distance: Int,
    ) : CombatLogEntry()

    /**
     * Hero braced for the turn via the basic Defend skill -
     * either picked manually under GRD or auto-promoted from
     * the default Attack when no enemy was reachable. [auto] is
     * true for the second case so the renderer can phrase it
     * differently ("...defends instead - no target in range.").
     */
    data class DefendBraced(
        val name: String,
        val auto: Boolean,
    ) : CombatLogEntry()

    /**
     * Hero tried to swing / cast but the chosen target sits
     * outside the skill's range. Posted INSTEAD of a hit / miss
     * line; the turn is NOT consumed so the player can re-pick
     * a different target or a longer-range skill.
     */
    data class OutOfRange(
        val attacker: String,
        val skillName: String,
        val target: String,
    ) : CombatLogEntry()

    /**
     * Target is within range but a wall or closed door breaks
     * the line of sight. Same no-turn-consumed semantics as
     * [OutOfRange]; the player needs to reposition or pick a
     * different target.
     */
    data class LineOfSightBlocked(
        val attacker: String,
        val skillName: String,
        val target: String,
    ) : CombatLogEntry()

    /**
     * Hero tried to use a skill that consumes an elemental shard
     * but the party had none in Ingredients. Turn is not consumed.
     */
    data class MissingShard(
        val attacker: String,
        val skillName: String,
        val shardName: String,
    ) : CombatLogEntry()

    /** Catch-all info line, e.g. "The Spear Goblin waits.". */
    data class Info(val text: String) : CombatLogEntry()

    /** Out-of-combat utility skill (camp, rest, cooking, make potion). */
    data class UtilitySkillUsed(
        val caster: String,
        val skillName: String,
    ) : CombatLogEntry()

    /** Party-wide HP / MP restored by a utility skill (second line after the cast). */
    data class UtilityRecoveryTotals(
        val totalHp: Int,
        val totalMp: Int,
    ) : CombatLogEntry()

    /** Item deposited into the party inventory after a utility cast. */
    data class ItemGained(val itemName: String) : CombatLogEntry()
}
