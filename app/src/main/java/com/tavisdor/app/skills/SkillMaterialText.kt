package com.tavisdor.app.skills

import android.content.Context
import com.tavisdor.app.R
import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.party.UtilitySkillResolver

/**
 * Player-facing copy for ingredient / shard gates on [Skill] entries.
 */
object SkillMaterialText {

    fun hasMaterialRequirement(skill: Skill): Boolean =
        skill.requiredShard != null || UtilitySkillResolver.ingredientCategoryFor(skill.id) != null

    fun format(context: Context, skill: Skill): String? {
        skill.requiredShard?.let { shard ->
            return context.getString(R.string.skillbook_material_named, shard.displayName)
        }
        val category = UtilitySkillResolver.ingredientCategoryFor(skill.id) ?: return null
        val options = Ingredient.inCategory(category).joinToString(", ") { it.displayName }
        return context.getString(
            R.string.skillbook_material_category,
            category.displayName,
            options,
        )
    }
}
