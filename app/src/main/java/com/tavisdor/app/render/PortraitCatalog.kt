package com.tavisdor.app.render

import com.tavisdor.app.party.Gender
import com.tavisdor.app.party.HeroClass

/**
 * Source of truth for which sprite files back each hero portrait.
 * Lives next to [HeroPanelRenderer] so the renderer doesn't need
 * to hard-code asset paths in two places.
 *
 * Three details worth knowing:
 *   1. Mage carries 4 idle frames (per the design request) where
 *      every other class has 2. The renderer treats both the same
 *      way - it just walks the [PortraitSpec.cycleAssets] list at
 *      a fixed cadence - so adding more frames later is data-only.
 *   2. Fighter MALE breaks the `{class}{M|F}pic{N}` naming the
 *      other classes follow: the on-disk files are `fighterpic1`
 *      / `fighterpic2` (no `M`). The hurt sprite still follows the
 *      convention (`fighterMhurt`). This catalog encodes that
 *      quirk so callers don't have to know.
 *   3. All paths are RELATIVE to `app/src/main/assets` and the
 *      sprites live under `sprites/...`. [HeroPanelRenderer] opens
 *      them through the [android.content.res.AssetManager].
 */
internal object PortraitCatalog {

    data class PortraitSpec(
        val cycleAssets: List<String>,
        val hurtAsset: String,
    )

    fun specFor(cls: HeroClass, gender: Gender): PortraitSpec = when (cls) {
        HeroClass.FIGHTER -> when (gender) {
            // Note: male fighter files drop the gender letter
            // (`fighterpic1` rather than `fighterMpic1`). Hurt
            // sprite still uses the M tag.
            Gender.MALE -> PortraitSpec(
                cycleAssets = listOf("sprites/fighterpic1.png", "sprites/fighterpic2.png"),
                hurtAsset = "sprites/fighterMhurt.png",
            )
            Gender.FEMALE -> PortraitSpec(
                cycleAssets = listOf("sprites/fighterFpic1.png", "sprites/fighterFpic2.png"),
                hurtAsset = "sprites/fighterFhurt.png",
            )
        }
        HeroClass.THIEF -> when (gender) {
            Gender.MALE -> PortraitSpec(
                cycleAssets = listOf("sprites/thiefMpic1.png", "sprites/thiefMpic2.png"),
                hurtAsset = "sprites/thiefMhurt.png",
            )
            Gender.FEMALE -> PortraitSpec(
                cycleAssets = listOf("sprites/thiefFpic1.png", "sprites/thiefFpic2.png"),
                hurtAsset = "sprites/thiefFhurt.png",
            )
        }
        HeroClass.ARCHER -> when (gender) {
            Gender.MALE -> PortraitSpec(
                cycleAssets = listOf("sprites/archerMpic1.png", "sprites/archerMpic2.png"),
                hurtAsset = "sprites/archerMhurt.png",
            )
            Gender.FEMALE -> PortraitSpec(
                cycleAssets = listOf("sprites/archerFpic1.png", "sprites/archerFpic2.png"),
                hurtAsset = "sprites/archerFhurt.png",
            )
        }
        HeroClass.MAGE -> when (gender) {
            // Mage gets 4 idle frames per the design brief; the
            // renderer just walks the list at the same cadence so
            // the longer cycle reads as a more elaborate resting
            // animation.
            Gender.MALE -> PortraitSpec(
                cycleAssets = listOf(
                    "sprites/mageMpic1.png",
                    "sprites/mageMpic2.png",
                    "sprites/mageMpic3.png",
                    "sprites/mageMpic4.png",
                ),
                hurtAsset = "sprites/mageMhurt.png",
            )
            Gender.FEMALE -> PortraitSpec(
                cycleAssets = listOf(
                    "sprites/mageFpic1.png",
                    "sprites/mageFpic2.png",
                    "sprites/mageFpic3.png",
                    "sprites/mageFpic4.png",
                ),
                hurtAsset = "sprites/mageFhurt.png",
            )
        }
    }
}
