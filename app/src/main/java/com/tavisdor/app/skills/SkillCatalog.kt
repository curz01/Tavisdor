package com.tavisdor.app.skills

import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.party.LevelProgression

/**
 * Authored skill / spell list per class. Source of truth for both the
 * unlock chart (level -> skill) and the effect descriptions, verbatim
 * from the design doc.
 *
 * Bucketing into the panel buttons (ACT / GRD / SPL) is computed from
 * [Skill.mpCost] + [Skill.castType] - see [Skill.button].
 */
object SkillCatalog {

    // ---------- MAGE ----------
    private val MAGE: List<Skill> = listOf(
        skill(
            id = "mage_fire_1", name = "Fire I", level = 1,
            type = SkillCastType.ACTIVE, range = 1, mp = 1,
            desc = "Fire damage 3, 1 mana. Less damage to Water type, more to Earth type.",
        ),
        skill(
            id = "mage_heal_1", name = "Heal I", level = 2,
            type = SkillCastType.ACTIVE, range = 1, mp = 1,
            desc = "Heal one target 3 HP, 1 mana. Cannot heal dead (someone at 0 HP).",
        ),
        skill(
            id = "mage_make_potion_1", name = "Make Potion I", level = 3,
            type = SkillCastType.PREPARE, range = 0, mp = 0,
            desc = "Turn a 'reagent' into a mana potion; output depends on reagent quality. " +
                "Out of combat only.",
        ),
        skill(
            id = "mage_fire_2", name = "Fire II", level = 4,
            type = SkillCastType.ACTIVE, range = 2, mp = 3,
            desc = "Fire damage 6, 3 mana. 50% chance to burn target next turn for 1 damage. " +
                "Less damage to Water type, more to Earth type.",
        ),
        skill(
            id = "mage_earth_1", name = "Earth I", level = 5,
            type = SkillCastType.ACTIVE, range = 1, mp = 1,
            desc = "Earth damage 2, 1 mana.",
        ),
        skill(
            id = "mage_heal_2", name = "Heal II", level = 6,
            type = SkillCastType.ACTIVE, range = 1, mp = 3,
            desc = "Heal one target 10 HP, 3 mana. Cannot heal dead.",
        ),
        skill(
            id = "mage_earth_2", name = "Earth II", level = 7,
            type = SkillCastType.ACTIVE, range = 1, mp = 3,
            desc = "Earth damage 4, 3 mana. 50% chance the enemy cannot move for 1 turn.",
        ),
        skill(
            id = "mage_fire_3", name = "Fire III", level = 8,
            type = SkillCastType.ACTIVE, range = 3, mp = 5,
            desc = "Fire damage 10, 5 mana. 85% chance to burn target for 2 turns at 3 damage. " +
                "Less damage to Water type, more to Earth type.",
        ),
        skill(
            id = "mage_earth_3", name = "Earth III", level = 9,
            type = SkillCastType.ACTIVE, range = 2, mp = 5,
            desc = "Earth damage 6, 5 mana. 75% chance the enemy cannot move for 2 turns; " +
                "25% chance the enemy cannot attack next turn.",
        ),
        skill(
            id = "mage_heal_3", name = "Heal III", level = 10,
            type = SkillCastType.ACTIVE, range = 1, mp = 5,
            desc = "Heal one target 20 HP, 5 mana. Residual heal of 5 HP next turn (does not " +
                "stack but can be refreshed). Cannot heal dead.",
        ),
    ).forClass(HeroClass.MAGE)

    // ---------- FIGHTER ----------
    private val FIGHTER: List<Skill> = listOf(
        skill(
            id = "fighter_heavy_strike", name = "Heavy Strike", level = 1,
            type = SkillCastType.ACTIVE, range = 1, mp = 0,
            desc = "Attack +2d3 damage. Cannot take an action next turn.",
        ),
        skill(
            id = "fighter_block", name = "Block", level = 2,
            type = SkillCastType.PREPARE, range = 0, mp = 0,
            desc = "Increase armor class by 2 (by 4 if equipping a shield) against the enemy's " +
                "next attack; lasts 1 turn.",
        ),
        skill(
            // ID kept as `fighter_defender` (saves stable); display name follows description doc.
            id = "fighter_defender", name = "Cover", level = 3,
            type = SkillCastType.PREPARE, range = 1, mp = 1,
            costsAction = false,
            desc = "Choose a target to cover; take all damage for them on the next attack. " +
                "Does not cost an action. 1 mana.",
        ),
        skill(
            id = "fighter_charge", name = "Charge", level = 4,
            type = SkillCastType.ACTIVE, range = 2, mp = 1,
            desc = "Move party 2 squares ahead (not diagonal) toward enemy and deal 50% damage. " +
                "1 mana.",
        ),
        skill(
            id = "fighter_thrust", name = "Thrust", level = 5,
            type = SkillCastType.ACTIVE, range = 1, mp = 0,
            desc = "Attack +1d3. 50% chance to also hit a target standing behind the current " +
                "enemy. If no enemy behind and no wall, pushes the enemy back 1 square.",
        ),
        skill(
            id = "fighter_armor_break", name = "Armor Break", level = 6,
            type = SkillCastType.ACTIVE, range = 1, mp = 1,
            desc = "Reduce the enemy's armor class by 30% for 2 turns. 1 mana. Higher chance if " +
                "this hero is not the highest-hate target.",
        ),
        skill(
            id = "fighter_disarm", name = "Disarm", level = 7,
            type = SkillCastType.ACTIVE, range = 1, mp = 1,
            desc = "Reduce the enemy's attack by 50% for 2 turns. 1 mana. Higher chance if this " +
                "hero is the highest-hate target.",
        ),
        skill(
            id = "fighter_camp", name = "Camp", level = 8,
            type = SkillCastType.PREPARE, range = 0, mp = 0,
            desc = "Uses up a 'camp' item to fully heal HP and MP for the party. Cannot heal " +
                "dead. 25% chance an enemy spawns and attacks the group.",
        ),
        skill(
            id = "fighter_shield_bash", name = "Shield Bash", level = 9,
            type = SkillCastType.ACTIVE, range = 1, mp = 0,
            desc = "If equipped with a shield, deal an additional attack at 50% damage. If no " +
                "shield, just a normal attack.",
        ),
        skill(
            id = "fighter_taunt", name = "Taunt", level = 10,
            type = SkillCastType.PREPARE, range = 2, mp = 0,
            desc = "Increase hate by 3.",
        ),
    ).forClass(HeroClass.FIGHTER)

    // ---------- THIEF ----------
    private val THIEF: List<Skill> = listOf(
        skill(
            id = "thief_feint", name = "Feint", level = 1,
            type = SkillCastType.PREPARE, range = 1, mp = 1,
            costsAction = false,
            desc = "Reduce hate level by 2. 1 mana. Does not cost an action.",
        ),
        skill(
            id = "thief_rest", name = "Rest", level = 2,
            type = SkillCastType.PREPARE, range = 0, mp = 1,
            desc = "Requires a 'beverage' item. Heals the party out of combat. 1 mana. " +
                "Cannot heal dead.",
        ),
        skill(
            id = "thief_evasive_maneuver", name = "Evasive Maneuver", level = 3,
            type = SkillCastType.PREPARE, range = 1, mp = 1,
            costsAction = false,
            desc = "Increase dodge by 20% for the next 2 turns. 1 mana. Does not cost an action.",
        ),
        skill(
            id = "thief_trick_attack", name = "Trick Attack", level = 4,
            type = SkillCastType.ACTIVE, range = 1, mp = 1,
            costsAction = false,
            desc = "Next attack deals +1d6 damage, and hate is added to another party member of " +
                "your choice. 1 mana. Does not cost an action.",
        ),
        skill(
            id = "thief_steal_item", name = "Steal Item", level = 5,
            type = SkillCastType.PREPARE, range = 1, mp = 1,
            desc = "Next successful attack deals no damage; instead steals 1 item from the " +
                "enemy's pool (does not reduce the end-of-encounter reward table). 1 mana.",
        ),
        skill(
            id = "thief_steal_gold", name = "Steal Gold", level = 6,
            type = SkillCastType.ACTIVE, range = 1, mp = 0,
            desc = "Chance to steal 50% of the gold the enemy is carrying. Works only once per " +
                "individual enemy.",
        ),
        skill(
            id = "thief_sneak_attack", name = "Sneak Attack", level = 7,
            type = SkillCastType.ACTIVE, range = 1, mp = 2,
            desc = "Attack +3d8. On a successful hit, hate is set to 5. 2 mana.",
        ),
        skill(
            // ID kept stable; display name follows description doc.
            id = "thief_weak_point", name = "Expose Weakness", level = 8,
            type = SkillCastType.PREPARE, range = 2, mp = 0,
            desc = "All party heroes have a 100% chance to hit this enemy (one time). The hero " +
                "performing this skill has no more actions this turn.",
        ),
        skill(
            id = "thief_double_strike", name = "Double Strike", level = 9,
            type = SkillCastType.PREPARE, range = 1, mp = 1,
            desc = "Next attack deals +2d6 damage. 1 mana.",
        ),
        skill(
            id = "thief_hide", name = "Hide", level = 10,
            type = SkillCastType.PREPARE, range = 1, mp = 0,
            desc = "Group is hidden from enemies 2 or more squares away. Must pass an INT + DEX " +
                "check against the enemy: combined hero stats must beat enemy combined 2 stats " +
                "+ (1d6 per level the enemy is higher than the hero).",
        ),
    ).forClass(HeroClass.THIEF)

    // ---------- ARCHER ----------
    private val ARCHER: List<Skill> = listOf(
        skill(
            id = "archer_feint_death", name = "Feint Death", level = 1,
            type = SkillCastType.ACTIVE, range = 0, mp = 1,
            costsAction = false,
            desc = "Reduce hate by 2. 1 mana. Does not cost an action.",
        ),
        skill(
            id = "archer_poison_arrow", name = "Poison Arrow", level = 2,
            type = SkillCastType.ACTIVE, range = 3, mp = 0,
            desc = "Normal damage; on poison success, enemy takes 2 damage each turn for the " +
                "next 2 turns.",
        ),
        skill(
            id = "archer_cooking", name = "Cooking", level = 3,
            type = SkillCastType.PREPARE, range = 0, mp = 0,
            desc = "Requires 'raw food'. Out of combat, heals the party HP up to 85%, depending " +
                "on the ingredient. Cannot heal dead.",
        ),
        skill(
            id = "archer_mark_target", name = "Mark Target", level = 4,
            type = SkillCastType.ACTIVE, range = 3, mp = 0,
            desc = "Damage done to the marked enemy is increased by 20%.",
        ),
        skill(
            id = "archer_rapid_fire", name = "Rapid Fire", level = 5,
            type = SkillCastType.PREPARE, range = 0, mp = 2,
            costsAction = false,
            desc = "80% chance to shoot more than 1 arrow next turn; 100% chance to shoot 2 " +
                "arrows. 2 mana. Does not cost an action.",
        ),
        skill(
            id = "archer_fire_arrow", name = "Fire Arrow", level = 6,
            type = SkillCastType.ACTIVE, range = 3, mp = 0,
            desc = "Normal damage + 1d6 fire damage. Increased damage to Earth, reduced damage " +
                "to Water.",
        ),
        skill(
            id = "archer_ice_arrow", name = "Ice Arrow", level = 7,
            type = SkillCastType.ACTIVE, range = 3, mp = 0,
            desc = "Normal damage + 1d6 ice damage. Increased damage to Fire, reduced damage " +
                "to Earth.",
        ),
        skill(
            // Cast type flipped to PREPARE per description doc.
            id = "archer_aim_shot", name = "Aim Shot", level = 8,
            type = SkillCastType.PREPARE, range = 4, mp = 0,
            desc = "Increase next damage by 150%. Cannot attack next turn. Hate increase by 2.",
        ),
        skill(
            // Marked passive per description doc ("always active").
            id = "archer_close_range", name = "Close-Range", level = 9,
            type = SkillCastType.PASSIVE, range = 0, mp = 0,
            desc = "Reduces the penalty for attacking in close range (an enemy touching the " +
                "party) by 25%. Always active.",
        ),
        skill(
            id = "archer_double_shot", name = "Double Shot", level = 10,
            type = SkillCastType.PREPARE, range = 3, mp = 1,
            desc = "Two attacks; the 2nd has a 25% miss chance. Hate +2 on a successful 2nd " +
                "attack. 1 mana.",
        ),
    ).forClass(HeroClass.ARCHER)

    init {
        // Defensive: each class table should grant one skill per level
        // from 1..MAX_LEVEL and have unique IDs across the whole catalog.
        listOf(MAGE, FIGHTER, THIEF, ARCHER).forEach { table ->
            val unlocks = table.map { it.unlockLevel }.toSet()
            val expected = (1..LevelProgression.MAX_LEVEL).toSet()
            require(unlocks == expected) {
                "Skill table for ${table.first().heroClass} must unlock one skill per level " +
                    "1..${LevelProgression.MAX_LEVEL}, got $unlocks"
            }
        }
        val allIds = (MAGE + FIGHTER + THIEF + ARCHER).map { it.id }
        require(allIds.toSet().size == allIds.size) {
            "Duplicate skill IDs detected in SkillCatalog: " +
                allIds.groupingBy { it }.eachCount().filter { it.value > 1 }
        }
    }

    // ---------- Public lookup API ----------

    /**
     * Stable id of the universal basic Attack every hero knows. Kept
     * as a constant so combat / save-load code can reference it
     * without re-typing the string.
     */
    const val BASIC_ATTACK_ID: String = "basic_attack"

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

    /** Full skill list for [cls], in unlock order. */
    fun allSkillsFor(cls: HeroClass): List<Skill> = when (cls) {
        HeroClass.MAGE -> MAGE
        HeroClass.FIGHTER -> FIGHTER
        HeroClass.THIEF -> THIEF
        HeroClass.ARCHER -> ARCHER
    }

    /**
     * Skills a level-[level] hero of [cls] currently knows (everything
     * with `unlockLevel <= level`).
     *
     * The universal [basicAttackFor] is prepended so every hero - of
     * every class, at every level - has an "Attack" option in the
     * Actions section of the picker / skills viewer.
     */
    fun knownSkillsFor(cls: HeroClass, level: Int): List<Skill> {
        val classKnown = allSkillsFor(cls).filter { it.unlockLevel <= level }
        return listOf(basicAttackFor(cls)) + classKnown
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
     * Recognises [BASIC_ATTACK_ID] explicitly because Attack lives
     * outside the per-class tables; the returned instance is tagged
     * to [HeroClass.FIGHTER] as a placeholder when no class context
     * is available - callers needing the right class should prefer
     * [basicAttackFor].
     */
    fun byId(id: String): Skill? {
        if (id == BASIC_ATTACK_ID) return basicAttackFor(HeroClass.FIGHTER)
        return allSkillsFor(HeroClass.MAGE).firstOrNull { it.id == id }
            ?: allSkillsFor(HeroClass.FIGHTER).firstOrNull { it.id == id }
            ?: allSkillsFor(HeroClass.THIEF).firstOrNull { it.id == id }
            ?: allSkillsFor(HeroClass.ARCHER).firstOrNull { it.id == id }
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
    )

    private fun List<Skill>.forClass(cls: HeroClass): List<Skill> =
        map { it.copy(heroClass = cls) }
}
