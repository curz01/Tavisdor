package com.tavisdor.app.enemies

/**
 * Elemental affinity tag attached to every [EnemyTemplate]. Drives the
 * future weakness / resistance system (e.g. Fire enemies take extra
 * damage from Water spells); for now it's just an authored label so
 * the data model is ready when combat math lands.
 *
 * [NEUTRAL] covers non-elemental enemies (skeletons, slimes, etc.) -
 * neither weak to nor resistant against any element.
 *
 * Add a new entry here when you introduce a new element family;
 * combat math will fall back to "no interaction" until the
 * weakness / resistance chart references it explicitly.
 */
enum class Element { FIRE, WATER, EARTH, AIR, NEUTRAL }
