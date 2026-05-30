package com.tavisdor.app.combat

/**
 * How an encounter resolved. [CombatController.onEnd] fires exactly
 * once with one of these values.
 */
enum class CombatEndResult {
    /** All enemies defeated — full victory flow. */
    VICTORY,
    /** Party successfully hid mid-fight — partial rewards, exploration. */
    HIDE_ESCAPE,
    /** Party wiped — respawn / penalty flow. */
    WIPE,
}
