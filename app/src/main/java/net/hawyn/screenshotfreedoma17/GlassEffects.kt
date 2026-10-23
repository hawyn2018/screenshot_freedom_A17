package net.hawyn.screenshotfreedoma17

/** 50% restores the previous surface; either endpoint remains continuously adjustable. */
internal fun lowerGlassSurfaceAlpha(base: Float, transparency: Float): Float {
    val t = transparency.coerceIn(0f, 1f)
    return if (t <= .5f) 1f + (base - 1f) * (t * 2f) else base * (2f - 2f * t)
}
