package com.tavisdor.app.items

import com.tavisdor.app.enemies.Element

/** Save / load encoding for [ItemSuffix] lists on weapons and armor. */
object ItemSuffixCodec {

    private const val ENTRY_SEP = ","
    private const val FIELD_SEP = ":"

    fun encode(suffixes: List<ItemSuffix>): String =
        if (suffixes.isEmpty()) "" else suffixes.joinToString(ENTRY_SEP) { encodeOne(it) }

    fun decode(raw: String?): List<ItemSuffix> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(ENTRY_SEP).mapNotNull { decodeOne(it) }
    }

    private fun encodeOne(suffix: ItemSuffix): String {
        val place = when (suffix.placement) {
            SuffixPlacement.MIDDLE -> "M"
            SuffixPlacement.OF -> "O"
        }
        val elem = suffix.element?.name ?: ""
        return listOf(
            suffix.kind.name,
            place,
            suffix.potency.toString(),
            elem,
        ).joinToString(FIELD_SEP)
    }

    private fun decodeOne(token: String): ItemSuffix? {
        val parts = token.split(FIELD_SEP)
        if (parts.size < 3) return null
        val kind = runCatching { ItemSuffixKind.valueOf(parts[0]) }.getOrNull() ?: return null
        val placement = when (parts[1]) {
            "M" -> SuffixPlacement.MIDDLE
            "O" -> SuffixPlacement.OF
            else -> return null
        }
        val potency = parts[2].toIntOrNull() ?: return null
        val element = if (parts.size >= 4 && parts[3].isNotEmpty()) {
            runCatching { Element.valueOf(parts[3]) }.getOrNull()
        } else {
            null
        }
        if (kind.needsElement && element == null) return null
        return ItemSuffix(kind, potency, placement, element)
    }
}
