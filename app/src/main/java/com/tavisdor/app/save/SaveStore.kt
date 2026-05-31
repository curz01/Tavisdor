package com.tavisdor.app.save

import android.content.Context
import android.content.SharedPreferences
import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.items.ArmorItem
import com.tavisdor.app.items.ArmorPieceSlot
import com.tavisdor.app.items.ArmorType
import com.tavisdor.app.items.ItemSuffixCodec
import com.tavisdor.app.items.LootTier
import com.tavisdor.app.items.WeaponType
import com.tavisdor.app.party.Gender
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.party.NameGenerator

/**
 * Persistent save store backed by [SharedPreferences]. Schema is intentionally
 * flat (one key per scalar field) for the scaffold so debugging on a real
 * device is trivial. When [SaveData] grows beyond a handful of fields
 * (mid-floor party cell, fog mask, per-room monster HP, inventory), migrate
 * the implementation to write a single JSON blob under one key versioned by
 * [SaveData.CURRENT_SCHEMA] - callers don't have to change.
 */
class SaveStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSave(): Boolean = prefs.getBoolean(KEY_HAS_SAVE, false)

    fun load(): SaveData? {
        if (!hasSave()) return null
        val schema = prefs.getInt(KEY_SCHEMA, SaveData.CURRENT_SCHEMA)
        val floor = prefs.getInt(KEY_CURRENT_FLOOR, 1)
        val seed = prefs.getLong(KEY_FLOOR_SEED, 0L)
        val heroes = (0 until 4).map { slot ->
            // Schema v1 saves do not have a name field; fall back to a deterministic
            // "HeroN" so a legacy save still loads instead of crashing.
            val nameKey = "$KEY_HERO_PREFIX${slot}_name"
            val storedName = prefs.getString(nameKey, null)
            val name = storedName ?: NameGenerator.fallback(slot)
            // Gender was added in schema v3; v1 / v2 saves silently
            // fall back to MALE so legacy parties still load with
            // a valid portrait sprite set instead of crashing on
            // an unknown enum name.
            val storedGender = prefs.getString("$KEY_HERO_PREFIX${slot}_gender", null)
            val gender = storedGender?.let { runCatching { Gender.valueOf(it) }.getOrNull() }
                ?: Gender.MALE
            HeroSaveData(
                name = name,
                heroClass = HeroClass.valueOf(
                    prefs.getString("$KEY_HERO_PREFIX${slot}_class", HeroClass.FIGHTER.name)
                        ?: HeroClass.FIGHTER.name
                ),
                gender = gender,
                level = prefs.getInt("$KEY_HERO_PREFIX${slot}_level", 1),
                xp = prefs.getInt("$KEY_HERO_PREFIX${slot}_xp", 0),
                maxHp = prefs.getInt("$KEY_HERO_PREFIX${slot}_maxhp", 10),
                hp = prefs.getInt("$KEY_HERO_PREFIX${slot}_hp", 10),
                dexterity = prefs.getInt("$KEY_HERO_PREFIX${slot}_dex", 10),
            )
        }
        // Schema v4 adds party-level gold + inventory. Older saves
        // default to 0 gold and empty inventory lists so a hero that
        // was farming on v3 doesn't crash on first load.
        val gold = prefs.getInt(KEY_PARTY_GOLD, 0)
        val weapons = decodeWeapons(prefs.getString(KEY_INV_WEAPONS, null))
        val ingredients = decodeIngredients(prefs.getString(KEY_INV_INGREDIENTS, null))
        val potions = if (schema >= 5) {
            decodePotions(prefs.getString(KEY_INV_POTIONS, null))
        } else {
            emptyList()
        }
        val armor = when {
            schema >= 7 -> decodeArmorStructured(prefs.getString(KEY_INV_ARMOR, null))
            schema >= 6 -> decodeArmorLegacy(prefs.getString(KEY_INV_ARMOR, null)).map { name ->
                val item = ArmorItem.fromLegacyDisplayName(name)
                ArmorSaveData(item.type, item.slot, item.plusLevel, item.suffixes)
            }
            else -> emptyList()
        }
        return SaveData(
            schemaVersion = schema,
            heroes = heroes,
            currentFloor = floor,
            floorSeed = seed,
            partyGold = gold,
            inventoryWeapons = weapons,
            inventoryIngredients = ingredients,
            inventoryPotions = potions,
            inventoryArmor = armor,
        )
    }

    fun save(data: SaveData) {
        prefs.edit().apply {
            putBoolean(KEY_HAS_SAVE, true)
            putInt(KEY_SCHEMA, data.schemaVersion)
            putInt(KEY_CURRENT_FLOOR, data.currentFloor)
            putLong(KEY_FLOOR_SEED, data.floorSeed)
            data.heroes.forEachIndexed { slot, h ->
                putString("$KEY_HERO_PREFIX${slot}_name", h.name)
                putString("$KEY_HERO_PREFIX${slot}_class", h.heroClass.name)
                putString("$KEY_HERO_PREFIX${slot}_gender", h.gender.name)
                putInt("$KEY_HERO_PREFIX${slot}_level", h.level)
                putInt("$KEY_HERO_PREFIX${slot}_xp", h.xp)
                putInt("$KEY_HERO_PREFIX${slot}_maxhp", h.maxHp)
                putInt("$KEY_HERO_PREFIX${slot}_hp", h.hp)
                putInt("$KEY_HERO_PREFIX${slot}_dex", h.dexterity)
            }
            putInt(KEY_PARTY_GOLD, data.partyGold)
            putString(KEY_INV_WEAPONS, encodeWeapons(data.inventoryWeapons))
            putString(KEY_INV_INGREDIENTS, encodeIngredients(data.inventoryIngredients))
            putString(KEY_INV_POTIONS, encodePotions(data.inventoryPotions))
            putString(KEY_INV_ARMOR, encodeArmor(data.inventoryArmor))
            apply()
        }
    }

    /**
     * Encodes a list of weapons as one row per weapon separated by
     * [WEAPON_ROW_SEPARATOR], with intra-row fields separated by
     * [WEAPON_FIELD_SEPARATOR]. Format:
     *
     *   `WeaponType.name | tierOrNull | attackBonus | range | plusLevel`
     *
     * The pipe character is reserved for the field separator;
     * [WeaponType] / [LootTier] enum names never contain it, and
     * [Weapon.displayName] is intentionally NOT serialized because
     * the load path recomposes it from the tier so a future
     * material-name rename retro-actively applies.
     */
    private fun encodeWeapons(list: List<WeaponSaveData>): String =
        if (list.isEmpty()) "" else list.joinToString(WEAPON_ROW_SEPARATOR) { w ->
            listOf(
                w.type.name,
                w.tier?.name ?: WEAPON_TIER_NONE,
                w.attackBonus.toString(),
                w.range.toString(),
                w.plusLevel.toString(),
                ItemSuffixCodec.encode(w.suffixes),
            ).joinToString(WEAPON_FIELD_SEPARATOR)
        }

    private fun decodeWeapons(raw: String?): List<WeaponSaveData> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(WEAPON_ROW_SEPARATOR).mapNotNull { row ->
            val parts = row.split(WEAPON_FIELD_SEPARATOR)
            if (parts.size !in 4..6) return@mapNotNull null
            val type = runCatching { WeaponType.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val tier = if (parts[1] == WEAPON_TIER_NONE) {
                null
            } else {
                runCatching { LootTier.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
            }
            val atk = parts[2].toIntOrNull() ?: return@mapNotNull null
            val rng = parts[3].toIntOrNull() ?: return@mapNotNull null
            val plus = when {
                parts.size >= 5 -> parts[4].toIntOrNull() ?: return@mapNotNull null
                else -> if (tier != null) (atk - tier.meleeWeaponBaseDamage).coerceAtLeast(0) else 0
            }
            val suffixes = if (parts.size >= 6) ItemSuffixCodec.decode(parts[5]) else emptyList()
            WeaponSaveData(type, tier, atk, rng, plus, suffixes)
        }
    }

    /**
     * Comma-separated [Ingredient.name]s. Order is preserved (the
     * panel renders them in pickup order). Unknown names are
     * dropped silently so a rename of an [Ingredient] enum doesn't
     * crash older saves - the player just loses that line.
     */
    private fun encodeIngredients(list: List<Ingredient>): String =
        if (list.isEmpty()) "" else list.joinToString(INGREDIENT_SEPARATOR) { it.name }

    private fun decodeIngredients(raw: String?): List<Ingredient> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(INGREDIENT_SEPARATOR).mapNotNull {
            runCatching { Ingredient.valueOf(it) }.getOrNull()
        }
    }

    /** Comma-separated reagent potency per potion (schema v5). */
    private fun encodePotions(list: List<com.tavisdor.app.items.Potion>): String =
        if (list.isEmpty()) "" else list.joinToString(INGREDIENT_SEPARATOR) { it.ingredientPotency.toString() }

    private fun decodePotions(raw: String?): List<com.tavisdor.app.items.Potion> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(INGREDIENT_SEPARATOR).mapNotNull { token ->
            val potency = token.toIntOrNull() ?: return@mapNotNull null
            com.tavisdor.app.items.Potion(ingredientPotency = potency)
        }
    }

    /** v8: type|slot|plus|suffixes. v7 rows (tier|plus|suffixes) still load. */
    private fun encodeArmor(list: List<ArmorSaveData>): String =
        if (list.isEmpty()) "" else list.joinToString(ARMOR_ROW_SEPARATOR) { a ->
            listOf(
                a.type.name,
                a.slot.name,
                a.plusLevel.toString(),
                ItemSuffixCodec.encode(a.suffixes),
            ).joinToString(ARMOR_FIELD_SEPARATOR)
        }

    private fun decodeArmorStructured(raw: String?): List<ArmorSaveData> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(ARMOR_ROW_SEPARATOR).mapNotNull { row ->
            if (!row.contains(ARMOR_FIELD_SEPARATOR)) {
                val legacy = ArmorItem.fromLegacyDisplayName(row)
                return@mapNotNull ArmorSaveData(
                    legacy.type, legacy.slot, legacy.plusLevel, legacy.suffixes,
                )
            }
            val parts = row.split(ARMOR_FIELD_SEPARATOR)
            val typeFromRow = runCatching { ArmorType.valueOf(parts[0]) }.getOrNull()
            if (typeFromRow != null && parts.size >= 3) {
                val slot = runCatching { ArmorPieceSlot.valueOf(parts[1]) }.getOrNull()
                    ?: return@mapNotNull null
                val plus = parts[2].toIntOrNull() ?: return@mapNotNull null
                val suffixes = if (parts.size >= 4) ItemSuffixCodec.decode(parts[3]) else emptyList()
                return@mapNotNull ArmorSaveData(typeFromRow, slot, plus, suffixes)
            }
            if (parts.size !in 2..3) return@mapNotNull null
            val tier = runCatching { LootTier.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val plus = parts[1].toIntOrNull() ?: return@mapNotNull null
            val suffixes = if (parts.size >= 3) ItemSuffixCodec.decode(parts[2]) else emptyList()
            ArmorSaveData(ArmorType.fromLootTier(tier), ArmorPieceSlot.ARMOR, plus, suffixes)
        }
    }

    private fun decodeArmorLegacy(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(ARMOR_ROW_SEPARATOR).filter { it.isNotEmpty() }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "tavisdor_save"
        private const val KEY_HAS_SAVE = "has_save"
        private const val KEY_SCHEMA = "schema_version"
        private const val KEY_CURRENT_FLOOR = "current_floor"
        private const val KEY_FLOOR_SEED = "floor_seed"
        private const val KEY_HERO_PREFIX = "hero_"

        // Schema v4 fields.
        private const val KEY_PARTY_GOLD = "party_gold"
        private const val KEY_INV_WEAPONS = "inv_weapons"
        private const val KEY_INV_INGREDIENTS = "inv_ingredients"
        private const val KEY_INV_POTIONS = "inv_potions"
        private const val KEY_INV_ARMOR = "inv_armor"

        // Inventory serialization separators. Chosen so the
        // characters never appear in enum names (which are the
        // only payload we serialize today).
        private const val WEAPON_ROW_SEPARATOR = ";"
        private const val WEAPON_FIELD_SEPARATOR = "|"
        private const val WEAPON_TIER_NONE = "_"
        private const val INGREDIENT_SEPARATOR = ","
        private const val ARMOR_ROW_SEPARATOR = ";"
        private const val ARMOR_FIELD_SEPARATOR = "|"
    }
}
