package com.tavisdor.app.render

/**
 * Intro choreography for out-of-combat utility casts before the frame
 * sequence runs and HP/MP recovery ticks begin.
 */
enum class UtilityCastMotion {
    /** Bottom-align on party icon, slide to [utilityFocusCell], then cycle. */
    CAMP_SLIDE_THEN_CYCLE,
    /** Hold frame 0 on party center, rise past the icon, then cycle. */
    RISE_THEN_CYCLE,
}
