package com.tavisdor.app.dungeon

/**
 * Runtime state for a single door cell on a [Floor]. Door positions come
 * from green pixels in the source room PNGs; locked-ness is rolled per
 * instance in [Floor.commitTemplate].
 *
 * Once a door is unlocked it stays unlocked for the lifetime of the floor;
 * there is no relock mechanic. Future extensions (key types, lock pick
 * difficulty, bash damage) belong here.
 */
class Door(var locked: Boolean)
