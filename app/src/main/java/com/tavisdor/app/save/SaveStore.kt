package com.tavisdor.app.save

import android.content.Context
import android.content.SharedPreferences
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
            HeroSaveData(
                name = name,
                heroClass = HeroClass.valueOf(
                    prefs.getString("$KEY_HERO_PREFIX${slot}_class", HeroClass.FIGHTER.name)
                        ?: HeroClass.FIGHTER.name
                ),
                level = prefs.getInt("$KEY_HERO_PREFIX${slot}_level", 1),
                xp = prefs.getInt("$KEY_HERO_PREFIX${slot}_xp", 0),
                maxHp = prefs.getInt("$KEY_HERO_PREFIX${slot}_maxhp", 10),
                hp = prefs.getInt("$KEY_HERO_PREFIX${slot}_hp", 10),
                dexterity = prefs.getInt("$KEY_HERO_PREFIX${slot}_dex", 10),
            )
        }
        return SaveData(schema, heroes, floor, seed)
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
                putInt("$KEY_HERO_PREFIX${slot}_level", h.level)
                putInt("$KEY_HERO_PREFIX${slot}_xp", h.xp)
                putInt("$KEY_HERO_PREFIX${slot}_maxhp", h.maxHp)
                putInt("$KEY_HERO_PREFIX${slot}_hp", h.hp)
                putInt("$KEY_HERO_PREFIX${slot}_dex", h.dexterity)
            }
            apply()
        }
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
    }
}
