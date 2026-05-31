package com.tavisdor.app.items

import com.tavisdor.app.enemies.Element

/**
 * Authored suffix types for weapons and armor. Each kind may appear
 * at most once per item. [middleName] / [ofName] come from the design
 * chart (typo "Intelegence" preserved on the of- form only).
 */
enum class ItemSuffixKind(
    val middleName: String,
    val ofName: String,
) {
    MIGHTY("Mighty", "Might"),
    HASTY("Hasty", "Haste"),
    INTELLECT("Intellect", "Intelegence"),
    ELEMENT("Element", "Elements"),
    VITAL("Vital", "Vitality"),
    MAGICAL("magical", "mana"),
    BLESSED("blessed", "blessing"),
    RECOVERING("recovering", "Recovery"),
    LEECHING("Leeching", "Stealing"),
    GAMBLING("gambling", "Chance"),
    SKILLED("skilled", "skills"),
    ;

    /** Suffixes that use [ItemSuffix.element] (Element / of Elements only). */
    val needsElement: Boolean get() = this == ELEMENT
}

enum class SuffixPlacement {
    /** Infix after the type, e.g. `Mithril Sword` **Hasty** `of Might`. */
    MIDDLE,
    /** Infix after the type, e.g. `Mithril Sword` **of Might**. */
    OF,
}

data class ItemSuffix(
    val kind: ItemSuffixKind,
    /** Potency N scales with dungeon depth at roll time. */
    val potency: Int,
    val placement: SuffixPlacement,
    /** Set when [kind] is [ItemSuffixKind.ELEMENT]. */
    val element: Element? = null,
) {
    init {
        require(!kind.needsElement || element != null) {
            "Element suffix requires an element"
        }
        require(potency >= 1)
    }

    fun displayFragment(): String = when (placement) {
        SuffixPlacement.MIDDLE -> kind.middleName
        SuffixPlacement.OF -> "of ${kind.ofName}"
    }
}
