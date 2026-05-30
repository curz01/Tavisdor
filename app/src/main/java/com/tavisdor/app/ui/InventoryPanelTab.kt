package com.tavisdor.app.ui

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.tavisdor.app.R

/**
 * Visual theme for one inventory panel tab (Loot / Equip / Mat).
 * The active tab lowers onto [R.id.itemsTabBody]; its accent colours
 * the panel frame and every grid slot border.
 */
enum class InventoryPanelTab(
    @DrawableRes val tabBackgroundRes: Int,
    @ColorRes val accentColorRes: Int,
) {
    PICKUP(
        tabBackgroundRes = R.drawable.bg_inventory_top_tab_pickup,
        accentColorRes = R.color.inventory_tab_pickup_selected,
    ),
    EQUIPMENT(
        tabBackgroundRes = R.drawable.bg_inventory_top_tab_equipment,
        accentColorRes = R.color.inventory_tab_equipment_selected,
    ),
    INGREDIENTS(
        tabBackgroundRes = R.drawable.bg_inventory_top_tab_ingredients,
        accentColorRes = R.color.inventory_tab_ingredients_selected,
    ),
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): InventoryPanelTab =
            entries.getOrElse(ordinal) { PICKUP }
    }
}
