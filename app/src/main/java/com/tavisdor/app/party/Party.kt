package com.tavisdor.app.party

import com.tavisdor.app.debug.DebugConfig
import com.tavisdor.app.items.ArmorItem
import com.tavisdor.app.items.Inventory
import com.tavisdor.app.items.ItemDisplayNames
import com.tavisdor.app.items.Weapon
import com.tavisdor.app.save.HeroSaveData

/**
 * The 4-hero party. Ordering is fixed and meaningful:
 *   index 0 = Front Line * left
 *   index 1 = Front Line * right
 *   index 2 = Back  Line * left
 *   index 3 = Back  Line * right
 *
 * The whole party always occupies a single grid cell on the dungeon floor
 * (the "party token"). Front/back distinction is purely logical - it drives
 * combat targeting and the bottom HUD layout, never physical positioning.
 */
class Party private constructor(
    heroes: List<Hero>,
) {
    private val _heroes: MutableList<Hero> = heroes.toMutableList()

    val heroes: List<Hero> get() = _heroes

    init {
        require(_heroes.size == 4) { "Party must contain exactly 4 heroes." }
    }

    /** Replaces one party member (equipment changes, etc.). */
    fun replaceHero(index: Int, hero: Hero) {
        require(index in _heroes.indices)
        _heroes[index] = hero
        inventory.notifyOwnerChanged()
    }

    val frontLine: List<Hero> get() = heroes.subList(0, 2)
    val backLine: List<Hero> get() = heroes.subList(2, 4)

    /**
     * Party gold. Bumped by combat (enemy `goldMin..goldMax` rolls
     * deposited on victory), treasure pickups, and any future
     * shop / quest reward path. Always non-negative; the depositors
     * are responsible for never asking for a negative amount.
     *
     * Mutations route through [addGold] so the inventory-change
     * listener fires on every increment - the items panel watches
     * a single source of truth ([Inventory.onChanged]) for both
     * gold updates and pickup-queue updates.
     */
    var gold: Int = 0
        private set

    /**
     * Shared inventory bag. Holds the equipment / weapons stash,
     * ingredient stack, and the transient pickup queue surfaced
     * in the items panel after combat or chest interactions.
     */
    val inventory: Inventory = Inventory()

    /**
     * Deposits [amount] gold and pings the inventory's change
     * listener so the items panel re-renders the counter without
     * needing a separate gold-specific subscription. Refuses
     * non-positive amounts as a defensive guard so loot-roll
     * bugs don't silently bleed the counter.
     */
    fun addGold(amount: Int) {
        if (amount <= 0) return
        gold += amount
        inventory.notifyOwnerChanged()
    }

    /**
     * Restore-time setter used by the load path so the saved gold
     * value can be put back without firing the panel listener
     * during view construction. Public to the [Party] companion
     * only - the load path is the lone caller.
     */
    internal fun restoreGold(amount: Int) {
        gold = amount.coerceAtLeast(0)
    }

    // ----- Party-wide derived values -----

    /**
     * Sum of [Hero.intelligence] across the whole party. Feeds the
     * shared XP-gain bonus; the design metaphor is "the smartness of
     * the group lets everyone learn faster."
     */
    val totalIntelligence: Int get() = heroes.sumOf { it.intelligence }

    /**
     * Party XP-gain bonus as a percentage. The curve is a staircase:
     *   - below [PARTY_INT_MIN_FOR_BONUS] (10) -> 0%
     *   - then every [PARTY_INT_PER_BONUS_STEP] (5) points of pooled
     *     INT crosses one tier worth [XP_BONUS_PCT_PER_STEP] (0.5%).
     *
     * Examples (matches the design doc):
     *   - INT  7  ->   0%
     *   - INT 10  -> 1.0%
     *   - INT 14  -> 1.0%   (no in-between bonus)
     *   - INT 15  -> 1.5%
     *   - INT 20  -> 2.0%
     *   - INT 50  -> 5.0%
     */
    val xpGainBonusPercent: Double
        get() {
            val total = totalIntelligence
            if (total < PARTY_INT_MIN_FOR_BONUS) return 0.0
            val steps = total / PARTY_INT_PER_BONUS_STEP
            return steps * XP_BONUS_PCT_PER_STEP
        }

    /**
     * Multiplier the XP-award path should multiply each hero's base XP
     * by. Applies uniformly to every party member.
     */
    val xpGainMultiplier: Double
        get() = 1.0 + xpGainBonusPercent / 100.0

    fun toSaveHeroes(): List<HeroSaveData> = heroes.map {
        // [Hero.dexterity] and [Hero.maxHp] are derived now; persisting
        // them keeps the schema unchanged but the values are informational
        // only - load re-derives both.
        HeroSaveData(
            name = it.name,
            heroClass = it.heroClass,
            gender = it.gender,
            level = it.level,
            xp = it.xp,
            maxHp = it.maxHp,
            hp = it.hp,
            dexterity = it.dexterity,
        )
    }

    companion object {
        /**
         * Below this much pooled party INT, no XP-gain bonus applies.
         * The first tier (1.0%) unlocks exactly at this threshold.
         */
        const val PARTY_INT_MIN_FOR_BONUS: Int = 10

        /**
         * Granularity of the XP-bonus staircase. The bonus only changes
         * each time pooled INT crosses a multiple of this value (5);
         * INT values between two steps grant the lower tier's bonus.
         */
        const val PARTY_INT_PER_BONUS_STEP: Int = 5

        /** Bonus percentage granted per [PARTY_INT_PER_BONUS_STEP] step. */
        const val XP_BONUS_PCT_PER_STEP: Double = 0.5

        fun create(drafts: List<HeroDraft>): Party {
            require(drafts.size == 4) { "Party.create requires exactly 4 hero drafts." }
            return Party(drafts.map { draft ->
                Hero.spawn(name = draft.name, cls = draft.heroClass, gender = draft.gender)
            })
        }

        /**
         * Convenience wrapper used by the load path. Restores the
         * heroes via the legacy hero-only entry point, then layers
         * the schema-v4 fields (gold + inventory contents) on top.
         */
        fun fromSaveData(saved: com.tavisdor.app.save.SaveData): Party {
            val party = fromSaveData(saved.heroes)
            party.restoreGold(saved.partyGold)
            val weapons = saved.inventoryWeapons.map { w ->
                val tier = w.tier
                val plus = w.plusLevel
                val suffixes = w.suffixes
                val displayName = if (tier != null) {
                    ItemDisplayNames.composeWeapon(tier, w.type, suffixes, plus)
                } else {
                    "Crude ${w.type.displayName}"
                }
                Weapon(
                    type = w.type,
                    tier = tier,
                    displayName = displayName,
                    attackBonus = w.attackBonus,
                    range = w.range,
                    plusLevel = plus,
                    suffixes = suffixes,
                )
            }
            val armor = saved.inventoryArmor.map {
                ArmorItem(it.type, it.slot, it.suffixes, it.plusLevel)
            }
            party.inventory.restore(
                weapons = weapons,
                ingredients = saved.inventoryIngredients,
                potions = saved.inventoryPotions,
                armor = armor,
            )
            return party
        }

        fun fromSaveData(saved: List<HeroSaveData>): Party {
            require(saved.size == 4) { "Saved party must contain exactly 4 heroes." }
            // While the debug level override is active, force every
            // loaded hero to [DebugConfig.STARTING_HERO_LEVEL]; otherwise
            // use the saved level from the file.
            val debugLevel = DebugConfig.STARTING_HERO_LEVEL
                .coerceIn(1, LevelProgression.MAX_LEVEL)
            return Party(saved.map {
                // STR / DEX / INT and maxHp / maxMp are derived from
                // (class, level), so the saved dexterity / maxHp values
                // are ignored. We only carry over the mutable runtime
                // state: level, xp, hp.
                val effectiveLevel = if (DebugConfig.isLevelOverrideActive) {
                    debugLevel
                } else {
                    it.level
                }
                val proto = Hero(
                    name = it.name,
                    heroClass = it.heroClass,
                    gender = it.gender,
                    level = effectiveLevel,
                    // Wipe XP toward the next level if the override
                    // pushed this hero up - the saved value belongs to
                    // the old (lower) level and would otherwise be
                    // displayed against a different denominator.
                    xp = if (effectiveLevel == it.level) it.xp else 0,
                    // Weapons aren't part of the save schema yet, so
                    // re-issue the crude starter on every load. Once
                    // looted weapons can be carried across saves this
                    // becomes "saved weapon ?: crude starter".
                    weapon1 = Weapon.crudeStarterFor(it.heroClass),
                )
                // Saved hp may exceed the freshly-derived maxHp (shouldn't,
                // but defend against a retuned chart) - clamp so the
                // hero never appears over-healed. If we just bumped the
                // hero's level the saved hp is from the old maxHp, so
                // top them off instead of leaving them looking damaged.
                val newHp = if (effectiveLevel == it.level) {
                    it.hp.coerceIn(0, proto.maxHp)
                } else {
                    proto.maxHp
                }
                proto.copy(hp = newHp, mp = proto.maxMp)
            })
        }
    }
}
