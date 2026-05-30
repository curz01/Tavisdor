package com.tavisdor.app.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.tavisdor.app.R

/** Builds inventory grid / panel frame drawables tinted by the active tab. */
object InventorySlotDrawables {

    fun slot(
        context: Context,
        @ColorInt accentColor: Int,
        filled: Boolean,
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val strokePx = (2f * density).toInt().coerceAtLeast(1)
        val radius = 6f * density
        val fill = ContextCompat.getColor(
            context,
            if (filled) R.color.inventory_slot_fill else R.color.inventory_slot_fill_empty,
        )
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(strokePx, accentColor)
        }
    }

    /** Frame around the tab body; top edge meets the lowered active tab. */
    fun tabBodyFrame(context: Context, @ColorInt accentColor: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val strokePx = (3f * density).toInt().coerceAtLeast(1)
        val bottomRadius = 10f * density
        val fill = ContextCompat.getColor(context, R.color.inventory_tab_body_fill)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(strokePx, accentColor)
            cornerRadii = floatArrayOf(
                0f, 0f,
                0f, 0f,
                bottomRadius, bottomRadius,
                bottomRadius, bottomRadius,
            )
        }
    }
}
