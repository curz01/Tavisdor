package com.tavisdor.app.items

/**
 * Top-level grouping for ingredient items. Each category exposes a
 * potency 1 / 2 / 3 progression (see [Ingredient]). The category is
 * primarily a UI / crafting-recipe sort key - loot rolls go through
 * [Ingredient.atPotency] directly.
 */
enum class IngredientCategory(val displayName: String) {
    CAMP("Camp"),
    BEVERAGE("Beverage"),
    RAW_MEAT("Raw Meat"),
    REAGENT("Reagent"),
}

/**
 * The authored ingredient list. Twelve items today: four categories
 * x three potency tiers (1 = weakest, 3 = strongest). New
 * ingredients append entries here; do not renumber existing ones
 * because save data (when ingredient persistence lands) may
 * reference them by ordinal.
 *
 * Enemy loot tables that say "drop a Level N random ingredient"
 * resolve to [atPotency] N - one of the four items at that tier.
 */
enum class Ingredient(
    val displayName: String,
    val category: IngredientCategory,
    val potency: Int,
) {
    // ----- Potency 1 -----
    BED_ROLL(      "Bed Roll",       IngredientCategory.CAMP,     1),
    BEER(          "Beer",           IngredientCategory.BEVERAGE, 1),
    RAW_RABBIT(    "Raw Rabbit",     IngredientCategory.RAW_MEAT, 1),
    HERBAL_ROOT(   "Herbal Root",    IngredientCategory.REAGENT,  1),

    // ----- Potency 2 -----
    SLEEP_SACK(    "Sleep Sack",     IngredientCategory.CAMP,     2),
    MEAD(          "Mead",           IngredientCategory.BEVERAGE, 2),
    RAW_CHICKEN(   "Raw Chicken",    IngredientCategory.RAW_MEAT, 2),
    MEDICINAL_ROOT("Medicinal Root", IngredientCategory.REAGENT,  2),

    // ----- Potency 3 -----
    TENT(          "Tent",           IngredientCategory.CAMP,     3),
    WINE(          "Wine",           IngredientCategory.BEVERAGE, 3),
    RAW_BEEF(      "Raw Beef",       IngredientCategory.RAW_MEAT, 3),
    GINSENG_ROOT(  "Ginseng Root",   IngredientCategory.REAGENT,  3),
    ;

    companion object {
        /**
         * Lowest authored potency. Below this, [atPotency] returns
         * an empty list and loot rolls should fall through to
         * "nothing dropped".
         */
        const val MIN_POTENCY: Int = 1

        /** Highest authored potency. Used for clamping deep-floor loot rolls. */
        const val MAX_POTENCY: Int = 3

        /**
         * Every ingredient at the given potency tier (1..3). Empty
         * if [potency] is outside the authored range; callers
         * should treat that as "no drop" rather than crashing.
         */
        fun atPotency(potency: Int): List<Ingredient> =
            entries.filter { it.potency == potency }

        /** Every ingredient in [category], potency 1 first. */
        fun inCategory(category: IngredientCategory): List<Ingredient> =
            entries.filter { it.category == category }.sortedBy { it.potency }
    }
}
