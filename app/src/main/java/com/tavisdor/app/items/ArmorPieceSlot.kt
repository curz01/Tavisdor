package com.tavisdor.app.items

/**
 * Equipment piece categories from the armor chart (A / S / H / B).
 * Shields may only be equipped by [com.tavisdor.app.party.HeroClass.FIGHTER],
 * regardless of whether the armor type lists [SHIELD].
 */
enum class ArmorPieceSlot(val chartCode: String, val displayName: String) {
    ARMOR("A", "Armor"),
    SHIELD("S", "Shield"),
    HELMET("H", "Helmet"),
    BOOTS("B", "Boots"),
    ;

    companion object {
        fun chartCodes(slots: Set<ArmorPieceSlot>): String =
            entries.filter { it in slots }.joinToString(", ") { it.chartCode }
    }
}
