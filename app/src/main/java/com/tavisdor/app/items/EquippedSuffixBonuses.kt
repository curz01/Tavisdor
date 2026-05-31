package com.tavisdor.app.items

import com.tavisdor.app.party.Hero

/**
 * Aggregates stat bonuses from suffixes on equipped weapon(s) and armor.
 * Prefix tier attack damage stays on [Weapon.attackBonus]; suffixes only
 * add the effects listed in the design chart.
 */
data class EquippedSuffixBonuses(
    val strength: Int = 0,
    val dexterity: Int = 0,
    val intelligence: Int = 0,
    val bonusMaxHp: Int = 0,
    val bonusMaxMp: Int = 0,
) {
    companion object {
        val NONE = EquippedSuffixBonuses()

        fun forHero(hero: Hero): EquippedSuffixBonuses {
            val suffixes = buildList {
                hero.weapon1?.suffixes?.let { addAll(it) }
                hero.weapon2?.suffixes?.let { addAll(it) }
                hero.armor?.suffixes?.let { addAll(it) }
            }
            return fromSuffixes(suffixes)
        }

        fun fromSuffixes(suffixes: List<ItemSuffix>): EquippedSuffixBonuses {
            var str = 0
            var dex = 0
            var int = 0
            var hp = 0
            var mp = 0
            for (suffix in suffixes) {
                val n = suffix.potency
                when (suffix.kind) {
                    ItemSuffixKind.MIGHTY -> str += n
                    ItemSuffixKind.HASTY -> dex += n
                    ItemSuffixKind.INTELLECT -> int += n
                    ItemSuffixKind.VITAL -> hp += n
                    ItemSuffixKind.MAGICAL -> mp += n
                    ItemSuffixKind.BLESSED -> {
                        str += n
                        dex += n
                        int += n
                        hp += n
                        mp += n
                    }
                    ItemSuffixKind.ELEMENT,
                    ItemSuffixKind.RECOVERING,
                    ItemSuffixKind.LEECHING,
                    ItemSuffixKind.GAMBLING,
                    ItemSuffixKind.SKILLED,
                    -> Unit
                }
            }
            return EquippedSuffixBonuses(str, dex, int, hp, mp)
        }
    }
}
