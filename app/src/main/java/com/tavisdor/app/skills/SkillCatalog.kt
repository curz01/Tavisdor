package com.tavisdor.app.skills

import com.tavisdor.app.enemies.Element
import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.items.WeaponType
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.party.LevelProgression

/**
 * Authored skill / spell list per class. Source of truth for both the
 * unlock chart (level -> skill) and the effect descriptions, verbatim
 * from the design doc.
 *
 * Bucketing into the panel buttons (ACT / GRD) is computed from
 * [Skill.castType] with optional per-skill overrides - see
 * [Skill.button]. The standalone SPL bucket was retired; spells now
 * surface under ACTION alongside other damage skills.
 */
object SkillCatalog {

    /**
     * Out-of-combat utility skill definitions (one per class at level 1).
     * Not part of per-class level-up unlock tables.
     */
    private val STARTING_UTILITY_SKILLS: List<Skill> = listOf(
        skill(
            id = "mage_make_potion_1", name = "Make Potion I", level = 1,
            type = SkillCastType.PREPARE, range = 0, mp = 0,
            desc = "Turn a 'reagent' into a mana potion; output depends on reagent quality. " +
                "Out of combat only.",
        ),
        skill(
            id = "fighter_camp", name = "Camp", level = 1,
            type = SkillCastType.PREPARE, range = 0, mp = 0,
            desc = "Uses up a 'camp' item to fully heal HP and MP for the party over the cast. " +
                "Cannot heal dead. 50% chance enemies ambush mid-cast (decided at start; " +
                "random recovery tick) so you may heal partially first. No ambush in a room " +
                "with stairs. Ambush size scales with floor depth (1–4 enemies).",
        ),
        skill(
            id = "thief_rest", name = "Rest", level = 1,
            type = SkillCastType.PREPARE, range = 0, mp = 0,
            desc = "Requires a 'beverage' item. Out of combat, restores each living hero " +
                "up to 50% / 60% / 70% of max HP and MP (potency 1 / 2 / 3). No effect if " +
                "already at or above the cap on both. No mana cost. Cannot heal dead.",
        ),
        skill(
            id = "archer_cooking", name = "Cooking", level = 1,
            type = SkillCastType.PREPARE, range = 0, mp = 0,
            desc = "Requires 'raw food'. Out of combat, restores party HP only (no MP), up to " +
                "70% / 80% / 90% of max (potency 1 / 2 / 3; e.g. Raw Rabbit). Consumes one " +
                "ingredient; creates nothing. Cannot heal dead.",
        ),
    )

    // ---------- MAGE ----------
    private val MAGE: List<Skill> = listOf(
        skill(
            id = "mage_fire_1", name = "Fire I", level = 1,
            type = SkillCastType.ACTIVE, range = 1, mp = 2,
            damage = 3, element = Element.FIRE,
            desc = "Fire damage 3, 2 mana. Less damage to Water type, more to Earth type.",
        ),
        skill(
            // Heal is not an attack - element stays null so combat
            // math routes it through a future heal resolver rather
            // than the damage / resist pipeline. ACTIVE but listed
            // under GRD (like Defend) because it's support, not offense.
            id = "mage_heal_1", name = "Heal I", level = 2,
            type = SkillCastType.ACTIVE, range = 1, mp = 1,
            desc = "Heal one target 3 HP, 1 mana. Cannot heal dead (someone at 0 HP).",
            button = SkillButton.GUARD,
        ),
        skill(
            id = "mage_earth_1", name = "Earth I", level = 3,
            type = SkillCastType.ACTIVE, range = 1, mp = 2,
            damage = 2, element = Element.EARTH,
            desc = "Earth damage 2, 2 mana.",
        ),
        skill(
            id = "mage_fire_2", name = "Fire II", level = 4,
            type = SkillCastType.ACTIVE, range = 2, mp = 4,
            damage = 6, element = Element.FIRE,
            desc = "Fire damage 6, 4 mana. 50% chance to burn target next turn for 1 damage. " +
                "Less damage to Water type, more to Earth type.",
        ),
        skill(
            id = "mage_heal_2", name = "Heal II", level = 5,
            type = SkillCastType.ACTIVE, range = 1, mp = 3,
            desc = "Heal one target 10 HP, 3 mana. Cannot heal dead.",
            button = SkillButton.GUARD,
        ),
        skill(
            id = "mage_earth_2", name = "Earth II", level = 6,
            type = SkillCastType.ACTIVE, range = 1, mp = 4,
            damage = 4, element = Element.EARTH,
            desc = "Earth damage 4, 4 mana. 50% chance the enemy cannot move for 1 turn.",
        ),
        skill(
            id = "mage_fire_3", name = "Fire III", level = 7,
            type = SkillCastType.ACTIVE, range = 3, mp = 6,
            damage = 10, element = Element.FIRE,
            desc = "Fire damage 10, 6 mana. 85% chance to burn target for 2 turns at 3 damage. " +
                "Less damage to Water type, more to Earth type.",
        ),
        skill(
            id = "mage_earth_3", name = "Earth III", level = 8,
            type = SkillCastType.ACTIVE, range = 2, mp = 6,
            damage = 6, element = Element.EARTH,
            desc = "Earth damage 6, 6 mana. 75% chance the enemy cannot move for 2 turns; " +
                "25% chance the enemy cannot attack next turn.",
        ),
        skill(
            id = "mage_heal_3", name = "Heal III", level = 9,
            type = SkillCastType.ACTIVE, range = 1, mp = 5,
            desc = "Heal one target 20 HP, 5 mana. Residual heal of 5 HP next turn (does not " +
                "stack but can be refreshed). Cannot heal dead.",
            button = SkillButton.GUARD,
        ),
    ).forClass(HeroClass.MAGE)

    // ---------- FIGHTER ----------
    // Damage on melee skills represents the bonus added to the
    // basic-attack damage (STR + weapon). Authored averages for
    // dice expressions in the description doc; the descriptive
    // string is left intact for flavor.
    private val FIGHTER: List<Skill> = listOf(
        skill(
            id = "fighter_taunt", name = "Taunt", level = 1,
            type = SkillCastType.PREPARE, range = 2, mp = 1,
            button = SkillButton.ACTION,
            desc = "Requires an enemy within range. Your threat +2 from each enemy; " +
                "each other living hero's threat -1. Uses your action.",
        ),
        skill(
            // ID kept as `fighter_defender` (saves stable).
            id = "fighter_defender", name = "Defender", level = 2,
            type = SkillCastType.PREPARE, range = 1, mp = 1,
            costsAction = false,
            desc = "Choose a target to cover; take all damage for them on the next attack. " +
                "Does not cost an action. 1 mana.",
        ),
        skill(
            id = "fighter_charge", name = "Charge", level = 3,
            type = SkillCastType.ACTIVE, range = 2, mp = 1,
            desc = "Move party 2 squares ahead (not diagonal) toward enemy and deal 50% damage. " +
                "1 mana.",
        ),
        skill(
            id = "fighter_heavy_strike", name = "Heavy Strike", level = 4,
            type = SkillCastType.ACTIVE, range = 1, mp = 0,
            damage = 4, // 2d3 average
            desc = "Attack +2d3 damage. Cannot take an action next turn.",
        ),
        skill(
            id = "fighter_thrust", name = "Thrust", level = 5,
            type = SkillCastType.ACTIVE, range = 1, mp = 0,
            damage = 2, // 1d3 average
            desc = "Attack +1d3. 50% chance to also hit a target standing behind the current " +
                "enemy. If no enemy behind and no wall, pushes the enemy back 1 square.",
        ),
        skill(
            id = "fighter_disarm", name = "Disarm", level = 6,
            type = SkillCastType.PREPARE, range = 0, mp = 1,
            costsAction = false,
            button = SkillButton.GUARD,
            desc = "Prepare as a free action, then attack: on a connecting hit, 50% chance to " +
                "reduce that enemy's attack by 50% for 2 turns (higher chance if you are the " +
                "highest-hate target). 1 mana. Does not cost an action.",
        ),
        skill(
            id = "fighter_armor_break", name = "Armor Break", level = 7,
            type = SkillCastType.ACTIVE, range = 1, mp = 1,
            desc = "Reduce the enemy's armor class by 30% for 2 turns. 1 mana. Higher chance if " +
                "this hero is not the highest-hate target.",
        ),
        skill(
            id = "fighter_counter_attack", name = "Counter Attack", level = 8,
            type = SkillCastType.PREPARE, range = 0, mp = 1,
            costsAction = false,
            button = SkillButton.GUARD,
            desc = "On the next melee swing against this hero: STR + DEX + threat vs the attacker; " +
                "on success, prevent damage (also triggers on a dodge or enemy fumble). Then " +
                "strike back with a free normal attack. 1 mana. Does not cost an action.",
        ),
        skill(
            id = "fighter_shield_bash", name = "Shield Bash", level = 9,
            type = SkillCastType.ACTIVE, range = 1, mp = 0,
            desc = "If equipped with a shield, deal an additional attack at 50% damage. If no " +
                "shield, just a normal attack.",
        ),
    ).forClass(HeroClass.FIGHTER)

    // ---------- THIEF ----------
    private val THIEF: List<Skill> = listOf(
        skill(
            id = "thief_lock_pick", name = "Lock Pick", level = 1,
            type = SkillCastType.PASSIVE, range = 1, mp = 1,
            desc = "Pick locked doors and chests within range 1, out of combat. Each attempt " +
                "costs 1 MP and one Stone Shard. DEX + 1d3 vs lock level for this dungeon depth.",
        ),
        skill(
            id = "thief_sneak_attack", name = "Sneak Attack", level = 2,
            type = SkillCastType.ACTIVE, range = 1, mp = 3,
            damage = 7, // 2d6 average
            desc = "Attack +2d6. On a successful hit, hate is set to 5. If you have the " +
                "highest threat on the target among other heroes, hit chance is halved. 3 mana.",
        ),
        skill(
            id = "thief_trick_attack", name = "Trick Attack", level = 3,
            type = SkillCastType.ACTIVE, range = 1, mp = 1,
            costsAction = false,
            damage = 4, // 1d6 average rounded down (3.5 -> 4 in author table)
            button = SkillButton.GUARD,
            desc = "Next attack deals +1d6 damage, and hate is added to another party member of " +
                "your choice. 1 mana. Does not cost an action.",
        ),
        skill(
            id = "thief_steal", name = "Steal", level = 4,
            type = SkillCastType.PREPARE, range = 1, mp = 1,
            costsAction = false,
            button = SkillButton.GUARD,
            desc = "Next connecting melee attack deals 50% damage, then tries to steal 1 item " +
                "(from a separate roll; does not reduce kill loot) and then tries to steal 50% " +
                "of the gold the enemy is carrying (once per enemy, does not reduce kill gold). " +
                "1 mana.",
        ),
        skill(
            id = "thief_hide", name = "Hide", level = 5,
            type = SkillCastType.PREPARE, range = 0, mp = 2,
            button = SkillButton.ACTION,
            desc = "Group is hidden from enemies 2 or more squares away. Must pass an INT + DEX " +
                "check against the enemy: combined hero stats must beat enemy combined 2 stats " +
                "+ (1d6 per level the enemy is higher than the hero). 2 mana. Uses your action.",
        ),
        skill(
            id = "thief_side_step", name = "Side Step", level = 6,
            type = SkillCastType.PASSIVE, range = 0, mp = 1,
            desc = "Passive. While any living party member knows this skill, the party may " +
                "move diagonally during combat (still one cell per move). Each diagonal step " +
                "costs 1 mana (paid by a living hero who knows Side Step).",
        ),
        skill(
            id = "thief_weak_point", name = "Weak Point", level = 7,
            type = SkillCastType.PREPARE, range = 2, mp = 2,
            button = SkillButton.ACTION,
            desc = "Mark an enemy within range. Each living hero may reroll once on their next " +
                "failed melee dodge or spell resist against that enemy (not on a natural 1). " +
                "2 mana. Uses your action.",
        ),
        skill(
            id = "thief_double_strike", name = "Double Strike", level = 8,
            type = SkillCastType.PREPARE, range = 1, mp = 3,
            damage = 7, // 2d6 average
            desc = "Next attack deals +2d6 damage. 3 mana.",
            button = SkillButton.ACTION,
        ),
        skill(
            id = "thief_evasive_maneuver", name = "Evasive Maneuver", level = 9,
            type = SkillCastType.PREPARE, range = 1, mp = 1,
            costsAction = false,
            desc = "Increase dodge by 20% for the next 2 turns. 1 mana. Does not cost an action.",
        ),
    ).forClass(HeroClass.THIEF)

    // ---------- ARCHER ----------
    private val ARCHER: List<Skill> = listOf(
        skill(
            id = "archer_aim_shot", name = "Aim Shot", level = 1,
            type = SkillCastType.PREPARE, range = 3, mp = 1,
            costsAction = false,
            desc = "Prepare as a free action, then attack: next damage +150%. If the defender " +
                "dodges the first swing, roll a second hit check. Hate +2 on a connecting hit. " +
                "Cannot take an action on your following turn.",
        ),
        skill(
            id = "archer_double_shot", name = "Double Shot", level = 2,
            type = SkillCastType.PREPARE, range = 2, mp = 1,
            costsAction = false,
            desc = "Next attack fires two arrows: first at 80% physical damage, second at 60%. " +
                "Elemental bonus damage is not reduced. If you run out of elemental shards " +
                "mid-attack, later arrows are normal. Does not stack with Rapid Fire. " +
                "1 mana. Does not cost an action.",
        ),
        skill(
            id = "archer_poison_arrow", name = "Poison Arrow", level = 3,
            type = SkillCastType.ACTIVE, range = 2, mp = 0,
            requiredShard = Ingredient.STONE_SHARD,
            desc = "Normal damage; on poison success, enemy takes 2 damage each turn for the " +
                "next 2 turns. Requires a Stone Shard.",
        ),
        skill(
            id = "archer_close_range", name = "Close-Range", level = 4,
            type = SkillCastType.PASSIVE, range = 0, mp = 0,
            desc = "Reduces the penalty for attacking in close range (an enemy on an adjacent " +
                "tile, including diagonals) by 25%. Always active.",
        ),
        skill(
            id = "archer_fire_arrow", name = "Fire Arrow", level = 5,
            type = SkillCastType.ACTIVE, range = 2, mp = 0,
            damage = 4, element = Element.FIRE,
            requiredShard = Ingredient.FLAME_SHARD,
            desc = "Normal damage + 1d6 fire damage. Increased damage to Earth, reduced damage " +
                "to Water. Requires a Flame Shard.",
        ),
        skill(
            id = "archer_ice_arrow", name = "Ice Arrow", level = 6,
            type = SkillCastType.ACTIVE, range = 2, mp = 0,
            damage = 4, element = Element.WATER, // ice resolves under WATER per the triangle
            requiredShard = Ingredient.HYDRO_SHARD,
            desc = "Normal damage + 1d6 ice damage. Increased damage to Fire, reduced damage " +
                "to Earth. Requires a Hydro Shard.",
        ),
        skill(
            id = "archer_feint_death", name = "Feint Death", level = 7,
            type = SkillCastType.ACTIVE, range = 0, mp = 1,
            desc = "Reduce hate by 2 toward you from each living enemy. 1 mana. Uses your action.",
        ),
        skill(
            id = "archer_rapid_fire", name = "Rapid Fire", level = 8,
            type = SkillCastType.PREPARE, range = 0, mp = 2,
            costsAction = false,
            desc = "Next attack fires 3 arrows. The 2nd has a 30% extra miss chance and the 3rd " +
                "50%; reroll-miss skills may retry those shots. Does not stack with Double Shot. " +
                "2 mana. Does not cost an action.",
        ),
        skill(
            id = "archer_mark_target", name = "Mark Target", level = 9,
            type = SkillCastType.ACTIVE, range = 3, mp = 1,
            desc = "Mark an enemy for 2 combat rounds. Damage to that enemy is increased by 20%. " +
                "1 mana.",
        ),
    ).forClass(HeroClass.ARCHER)

    init {
        // Defensive: each class table grants one combat skill per level 1..9
        // (utilities are in [STARTING_UTILITY_SKILLS]; level 10 is stat-only).
        val progressionLevels = 1 until LevelProgression.MAX_LEVEL
        listOf(MAGE, FIGHTER, THIEF, ARCHER).forEach { table ->
            val unlocks = table.map { it.unlockLevel }.toSet()
            val expected = progressionLevels.toSet()
            require(unlocks == expected) {
                "Skill table for ${table.first().heroClass} must unlock one skill per level " +
                    "$progressionLevels, got $unlocks"
            }
        }
        val allIds = (MAGE + FIGHTER + THIEF + ARCHER + STARTING_UTILITY_SKILLS).map { it.id }
        require(allIds.toSet().size == allIds.size) {
            "Duplicate skill IDs detected in SkillCatalog: " +
                allIds.groupingBy { it }.eachCount().filter { it.value > 1 }
        }
    }

    // ---------- Public lookup API ----------

    const val MAGE_EARTH_1_ID: String = "mage_earth_1"
    const val MAGE_EARTH_2_ID: String = "mage_earth_2"
    const val MAGE_EARTH_3_ID: String = "mage_earth_3"
    const val MAGE_FIRE_1_ID: String = "mage_fire_1"
    const val MAGE_FIRE_2_ID: String = "mage_fire_2"
    const val MAGE_FIRE_3_ID: String = "mage_fire_3"

    /**
     * Stable id of the universal basic Attack every hero knows. Kept
     * as a constant so combat / save-load code can reference it
     * without re-typing the string.
     */
    const val BASIC_ATTACK_ID: String = "basic_attack"

    /**
     * Stable id of the universal basic Defend every hero knows.
     * Pair-counterpart of [BASIC_ATTACK_ID]: Attack is the baseline
     * option under ACT, Defend is the baseline option under GRD.
     */
    const val BASIC_DEFEND_ID: String = "basic_defend"

    /** Thief passive: pick locks on doors and chests (DEX + 1d3 vs lock level). */
    const val THIEF_LOCK_PICK_ID: String = "thief_lock_pick"

    /** Party-wide: diagonal one-cell moves during combat. */
    const val THIEF_SIDE_STEP_ID: String = "thief_side_step"

    const val MAGE_MAKE_POTION_ID: String = "mage_make_potion_1"
    const val FIGHTER_CAMP_ID: String = "fighter_camp"
    const val THIEF_REST_ID: String = "thief_rest"
    const val ARCHER_COOKING_ID: String = "archer_cooking"

    /**
     * Out-of-combat / utility skills listed in the assignment panel's
     * PASSIVE column (still stageable for the Action button). Not
     * [SkillCastType.PASSIVE] mechanically.
     */
    val ASSIGN_PASSIVE_COLUMN_SKILL_IDS: Set<String> = setOf(
        "archer_cooking",
        "mage_make_potion_1",
        "fighter_camp",
        "thief_rest",
        THIEF_LOCK_PICK_ID,
    )

    /** AC bonus granted by the universal Defend skill for one turn. */
    const val BASIC_DEFEND_AC_BONUS: Int = 2

    /**
     * Stable id of the Fighter's "Charge" skill. The combat
     * controller treats this id specially: instead of going
     * through the regular swing-in-place flow it lunges the
     * party up to [Skill.range] cells toward the target along a
     * cardinal path and then strikes for
     * [FIGHTER_CHARGE_DAMAGE_PCT]% of normal melee damage.
     */
    const val FIGHTER_CHARGE_ID: String = "fighter_charge"

    /** Fighter prepare: shifts hate toward the taunter and away from allies. */
    const val FIGHTER_TAUNT_ID: String = "fighter_taunt"

    /** Fighter prepare: blocks the next incoming melee hit, then ripostes. */
    const val FIGHTER_COUNTER_ATTACK_ID: String = "fighter_counter_attack"

    /** Fighter prepare: next connecting offensive hit may disarm the target. */
    const val FIGHTER_DISARM_ID: String = "fighter_disarm"

    const val FIGHTER_DISARM_DURATION_TURNS: Int = 2

    const val FIGHTER_DISARM_ATTACK_REDUCTION_PCT: Int = 50

    /** Success when this hero tops threat on the target. */
    const val FIGHTER_DISARM_SUCCESS_PCT_HIGH: Int = 75

    /** Success when this hero does not top threat on the target. */
    const val FIGHTER_DISARM_SUCCESS_PCT_LOW: Int = 40

    /** Thief prepare: marked enemy grants each hero one miss reroll (melee or spell). */
    const val THIEF_WEAK_POINT_ID: String = "thief_weak_point"

    const val THIEF_SNEAK_ATTACK_ID: String = "thief_sneak_attack"

    const val THIEF_DOUBLE_STRIKE_ID: String = "thief_double_strike"

    const val THIEF_TRICK_ATTACK_ID: String = "thief_trick_attack"

    const val THIEF_HIDE_ID: String = "thief_hide"

    const val THIEF_EVASIVE_MANEUVER_ID: String = "thief_evasive_maneuver"

    /** Dodge bonus percent while [THIEF_EVASIVE_MANEUVER_ID] is active (enemy melee). */
    const val THIEF_EVASIVE_MANEUVER_DODGE_BONUS_PCT: Int = 20

    /** Hero turns the buff lasts after it is committed. */
    const val THIEF_EVASIVE_MANEUVER_DURATION_TURNS: Int = 2

    /** Archer prepare: three arrows on the hero's next offensive commit. */
    const val ARCHER_RAPID_FIRE_ID: String = "archer_rapid_fire"

    const val ARCHER_RAPID_FIRE_ARROW_COUNT: Int = 3

    /** Extra miss chance (0..100) on the 2nd Rapid Fire arrow (after the hit roll). */
    const val ARCHER_RAPID_FIRE_SECOND_ARROW_MISS_PCT: Int = 30

    /** Extra miss chance (0..100) on the 3rd Rapid Fire arrow (after the hit roll). */
    const val ARCHER_RAPID_FIRE_THIRD_ARROW_MISS_PCT: Int = 50

    /** Archer prepare: two arrows on the next offensive commit. */
    const val ARCHER_DOUBLE_SHOT_ID: String = "archer_double_shot"

    /** Physical damage percent for the first Double Shot arrow. */
    const val ARCHER_DOUBLE_SHOT_FIRST_DAMAGE_PCT: Int = 80

    /** Physical damage percent for the second Double Shot arrow. */
    const val ARCHER_DOUBLE_SHOT_SECOND_DAMAGE_PCT: Int = 60

    /** Archer prepare staged as a free action; buffs the next attack. */
    const val ARCHER_AIM_SHOT_ID: String = "archer_aim_shot"

    const val ARCHER_FEINT_DEATH_ID: String = "archer_feint_death"

    /** Hate delta applied toward the caster from each living enemy. */
    const val ARCHER_FEINT_DEATH_HATE_DELTA: Int = -2

    const val ARCHER_MARK_TARGET_ID: String = "archer_mark_target"

    /** Bonus damage percent while [ARCHER_MARK_TARGET_ID] is active. */
    const val ARCHER_MARK_TARGET_DAMAGE_BONUS_PCT: Int = 20

    /** How many full combat rounds the mark lasts (including the round it is applied). */
    const val ARCHER_MARK_TARGET_DURATION_ROUNDS: Int = 2

    const val ARCHER_CLOSE_RANGE_ID: String = "archer_close_range"

    const val ARCHER_FIRE_ARROW_ID: String = "archer_fire_arrow"
    const val ARCHER_POISON_ARROW_ID: String = "archer_poison_arrow"
    const val ARCHER_ICE_ARROW_ID: String = "archer_ice_arrow"

    /**
     * Extra miss chance (0–100) when an archer fires while an enemy is
     * on a tile adjacent to the party (cardinal or diagonal) with any bow.
     * [ARCHER_CLOSE_RANGE_ID] multiplies this
     * by `(100 - [ARCHER_CLOSE_RANGE_PENALTY_RELIEF_PCT]) / 100`.
     */
    const val ARCHER_CLOSE_RANGE_BASE_PENALTY_PCT: Int = 50

    /** Percent shaved off the close-range penalty (authored: "by 25%"). */
    const val ARCHER_CLOSE_RANGE_PENALTY_RELIEF_PCT: Int = 25

    fun archerCloseRangeMissPenaltyPct(hasCloseRangeSkill: Boolean): Int {
        val base = ARCHER_CLOSE_RANGE_BASE_PENALTY_PCT
        if (!hasCloseRangeSkill) return base
        return (base * (100 - ARCHER_CLOSE_RANGE_PENALTY_RELIEF_PCT) / 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private val ARCHER_ELEMENTAL_ARROW_IDS: Set<String> = setOf(
        ARCHER_FIRE_ARROW_ID,
        ARCHER_POISON_ARROW_ID,
        ARCHER_ICE_ARROW_ID,
    )

    fun isArcherElementalArrow(skillId: String): Boolean =
        skillId in ARCHER_ELEMENTAL_ARROW_IDS

    /** Archer bow skills that gain +1 range when a bow is equipped (see [WeaponClassRules]). */
    fun receivesArcherBowRangeBonus(skillId: String): Boolean =
        skillId in ARCHER_ELEMENTAL_ARROW_IDS || skillId == ARCHER_AIM_SHOT_ID

    /** Chance (0..100) that Poison Arrow applies its poison after a connecting shot. */
    const val ARCHER_POISON_ARROW_SUCCESS_PCT: Int = 50

    const val ARCHER_POISON_ARROW_DAMAGE_PER_TURN: Int = 2

    /** Poison ticks at the start of each of the enemy's turns. */
    const val ARCHER_POISON_ARROW_DURATION_TURNS: Int = 2

    /** Bow projectile sprite for Fire / Poison / Ice Arrow; null for other skills. */
    fun arrowAssetForArcherArrow(skillId: String): String? = when (skillId) {
        ARCHER_FIRE_ARROW_ID -> "fire_arrow"
        ARCHER_POISON_ARROW_ID -> "poison_arrow"
        ARCHER_ICE_ARROW_ID -> "ice_arrow"
        else -> null
    }

    /** True passives are info-only in the assign panel; PREPARE skills stage normally. */
    fun isStageableInAssignPanel(skill: Skill): Boolean = !skill.isPassive

    /**
     * Charge damage as a percentage of a normal basic-attack
     * swing (STR + weapon). Authored at 50% per the design
     * doc - the lower damage is the cost of paying 1 MP to also
     * close a 2-cell gap.
     */
    const val FIGHTER_CHARGE_DAMAGE_PCT: Int = 50

    /** Thief prepare: next connecting melee hit steals item then gold at reduced damage. */
    const val THIEF_STEAL_ID: String = "thief_steal"

    /** Melee damage multiplier while the Steal buff is active. */
    const val THIEF_STEAL_DAMAGE_PCT: Int = 50

    /** Fraction of [enemyCarriedGold] taken on a successful gold steal. */
    const val THIEF_STEAL_GOLD_FRACTION_PCT: Int = 50

    /** Chance (0..100) that the gold-steal attempt succeeds. */
    const val THIEF_STEAL_GOLD_CHANCE_PCT: Int = 50

    /**
     * The default "Attack" action every hero has access to from
     * level 1, regardless of class. Lives outside the per-class
     * tables on purpose: it's not a level-up unlock and it shouldn't
     * count against the "one new skill per level" invariant.
     *
     * Class-tinted only to keep the [Skill.heroClass] field non-null;
     * combat logic should look up by [BASIC_ATTACK_ID] rather than
     * branching on class.
     */
    fun basicAttackFor(cls: HeroClass): Skill = Skill(
        id = BASIC_ATTACK_ID,
        displayName = "Attack",
        heroClass = cls,
        castType = SkillCastType.ACTIVE,
        range = 1,
        unlockLevel = 1,
        mpCost = 0,
        costsAction = true,
        description = "Standard attack against an adjacent enemy. " +
            "No mana cost; uses the hero's main action.",
    )

    /** Player-facing basic attack label, e.g. `Attack (Sword)` — weapon type only, no tier prefix. */
    fun basicAttackDisplayName(weaponType: WeaponType): String =
        "Attack (${weaponType.displayName})"

    /**
     * The default "Defend" action every hero has access to from
     * level 1, regardless of class. Mirrors [basicAttackFor] but
     * lives under the GUARD button via [Skill.buttonOverride] -
     * cast type is ACTIVE so it resolves on the same turn it's
     * picked.
     *
     * Effect: +[BASIC_DEFEND_AC_BONUS] armor class for this turn.
     * Consumes the hero's action (you can either swing or brace,
     * not both), matching the chart's "uses up their action" note.
     */
    fun basicDefendFor(cls: HeroClass): Skill = Skill(
        id = BASIC_DEFEND_ID,
        displayName = "Defend",
        heroClass = cls,
        castType = SkillCastType.ACTIVE,
        range = 0,
        unlockLevel = 1,
        mpCost = 0,
        costsAction = true,
        description = "Brace this turn for +$BASIC_DEFEND_AC_BONUS armor class. " +
            "No mana cost; uses the hero's main action.",
        buttonOverride = SkillButton.GUARD,
    )

    /** Full skill list for [cls], in unlock order. */
    fun allSkillsFor(cls: HeroClass): List<Skill> = when (cls) {
        HeroClass.MAGE -> MAGE
        HeroClass.FIGHTER -> FIGHTER
        HeroClass.THIEF -> THIEF
        HeroClass.ARCHER -> ARCHER
    }

    /** Class utility known from level 1 (Mage: potion, Fighter: camp, etc.). */
    fun startingUtilitiesFor(cls: HeroClass): List<Skill> {
        val id = when (cls) {
            HeroClass.MAGE -> MAGE_MAKE_POTION_ID
            HeroClass.FIGHTER -> FIGHTER_CAMP_ID
            HeroClass.THIEF -> THIEF_REST_ID
            HeroClass.ARCHER -> ARCHER_COOKING_ID
        }
        val skill = STARTING_UTILITY_SKILLS.firstOrNull { it.id == id } ?: return emptyList()
        return listOf(skill.copy(heroClass = cls))
    }

    /**
     * Skills a level-[level] hero of [cls] currently knows (everything
     * with `unlockLevel <= level`, plus [startingUtilitiesFor]).
     *
     * Both universal baselines - [basicAttackFor] and [basicDefendFor] -
     * are prepended so every hero, of every class, at every level,
     * has an Attack option under ACT and a Defend option under GRD
     * in the picker / skills viewer.
     */
    fun knownSkillsFor(cls: HeroClass, level: Int): List<Skill> {
        val classKnown = allSkillsFor(cls).filter { it.unlockLevel <= level }
        return listOf(basicAttackFor(cls), basicDefendFor(cls)) +
            startingUtilitiesFor(cls) +
            classKnown
    }

    /**
     * Subset of [knownSkillsFor] filtered to a single button bucket.
     * Used by the bottom hero panel when the player taps ACT / GRD / SPL.
     */
    fun knownSkillsFor(cls: HeroClass, level: Int, button: SkillButton): List<Skill> =
        knownSkillsFor(cls, level).filter { it.button == button }

    /**
     * The skill freshly unlocked when a hero of [cls] reaches [level].
     * Returns `null` for levels outside the authored 1..MAX_LEVEL range.
     * Basic Attack is intentionally excluded - it's not a "new" unlock.
     */
    fun unlockedAt(cls: HeroClass, level: Int): Skill? =
        allSkillsFor(cls).firstOrNull { it.unlockLevel == level }

    /**
     * Lookup by stable [Skill.id]; null if no such skill exists.
     *
     * Recognises [BASIC_ATTACK_ID] and [BASIC_DEFEND_ID] explicitly
     * because both live outside the per-class tables; the returned
     * instances are tagged to [HeroClass.FIGHTER] as a placeholder
     * when no class context is available - callers needing the
     * right class should prefer [basicAttackFor] / [basicDefendFor].
     */
    fun byId(id: String): Skill? {
        if (id == BASIC_ATTACK_ID) return basicAttackFor(HeroClass.FIGHTER)
        if (id == BASIC_DEFEND_ID) return basicDefendFor(HeroClass.FIGHTER)
        val resolvedId = when (id) {
            "thief_steal_item", "thief_steal_gold" -> THIEF_STEAL_ID
            else -> id
        }
        return STARTING_UTILITY_SKILLS.firstOrNull { it.id == resolvedId }
            ?: allSkillsFor(HeroClass.MAGE).firstOrNull { it.id == resolvedId }
            ?: allSkillsFor(HeroClass.FIGHTER).firstOrNull { it.id == resolvedId }
            ?: allSkillsFor(HeroClass.THIEF).firstOrNull { it.id == resolvedId }
            ?: allSkillsFor(HeroClass.ARCHER).firstOrNull { it.id == resolvedId }
    }

    // ---------- Builder helper ----------

    private fun skill(
        id: String,
        name: String,
        level: Int,
        type: SkillCastType,
        range: Int,
        mp: Int = 0,
        costsAction: Boolean = true,
        desc: String = "",
        damage: Int? = null,
        element: Element? = null,
        button: SkillButton? = null,
        requiredShard: Ingredient? = null,
    ): Skill = Skill(
        id = id,
        displayName = name,
        heroClass = HeroClass.MAGE, // placeholder, overwritten by forClass()
        castType = type,
        range = range,
        unlockLevel = level,
        mpCost = mp,
        costsAction = costsAction,
        description = desc,
        damage = damage,
        element = element,
        buttonOverride = button,
        requiredShard = requiredShard,
    )

    private fun List<Skill>.forClass(cls: HeroClass): List<Skill> =
        map { it.copy(heroClass = cls) }
}
