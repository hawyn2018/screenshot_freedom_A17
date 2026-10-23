package net.hawyn.screenshotfreedoma17

import android.content.Context

/** UI appearance belongs to the app; screenshot settings are owned by CompatController. */
data class Appearance(
    val mode: String = "system",
    val monet: Boolean = true,
    val followAccent: Boolean = true,
    val accent: Long = 0xFF3478F6,
    val palette: String = "TonalSpot",
    val colorSpec: String = "Spec2025",
    val floatingBar: Boolean = true,
    val liquidGlass: Boolean = true,
    // GLASS_APPEARANCE_FIELDS_BEGIN
    val lowerGlassTransparency: Float = .5f,
    val lowerGlassColorStrength: Float = 1f,
    val upperGlassTransparency: Float = .93f,
    val upperGlassColor: Long = 0xFF000000,
    // GLASS_APPEARANCE_FIELDS_END
)

class UiPreferences(context: Context) {
    private val preferences = context.applicationContext.createDeviceProtectedStorageContext()
        .getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun read() = Appearance(
        mode = preferences.getString("mode", "system") ?: "system",
        monet = preferences.getBoolean("monet", true),
        followAccent = preferences.getBoolean("follow_accent", true),
        accent = preferences.getLong("accent", 0xFF3478F6),
        palette = preferences.getString("palette", "TonalSpot") ?: "TonalSpot",
        colorSpec = preferences.getString("color_spec", "Spec2025") ?: "Spec2025",
        floatingBar = preferences.getBoolean("floating_bar", true),
        liquidGlass = preferences.getBoolean("liquid_glass", true),
        // GLASS_APPEARANCE_READ_BEGIN
        lowerGlassTransparency = preferences.getFloat("lower_glass_transparency", .5f)
            .let { if (it.isFinite()) it.coerceIn(0f, 1f) else .5f },
        lowerGlassColorStrength = preferences.getFloat("lower_glass_color_strength", 1f)
            .let { if (it.isFinite()) it.coerceIn(0f, 2f) else 1f },
        upperGlassTransparency = preferences.getFloat("solid_upper_transparency_v2",
            preferences.getFloat("solid_upper_transparency", .93f).let { if (it == .7f) .93f else it })
            .let { if (it.isFinite()) it.coerceIn(0f, 1f) else .93f },
        upperGlassColor = 0xFF000000 or (preferences.getLong("solid_upper_color", 0xFF000000) and 0xFFFFFF),
        // GLASS_APPEARANCE_READ_END
    )

    fun write(value: Appearance) {
        preferences.edit().putString("mode", value.mode).putBoolean("monet", value.monet)
            .putBoolean("follow_accent", value.followAccent).putLong("accent", value.accent)
            .putString("palette", value.palette).putString("color_spec", value.colorSpec)
            .putBoolean("floating_bar", value.floatingBar).putBoolean("liquid_glass", value.liquidGlass)
            // GLASS_APPEARANCE_WRITE_BEGIN
            .putFloat("lower_glass_transparency", value.lowerGlassTransparency.let { if (it.isFinite()) it.coerceIn(0f, 1f) else .5f })
            .putFloat("lower_glass_color_strength", value.lowerGlassColorStrength.let { if (it.isFinite()) it.coerceIn(0f, 2f) else 1f })
            .putFloat("solid_upper_transparency_v2", value.upperGlassTransparency.let { if (it.isFinite()) it.coerceIn(0f, 1f) else .93f })
            .putLong("solid_upper_color", 0xFF000000 or (value.upperGlassColor and 0xFFFFFF))
            // GLASS_APPEARANCE_WRITE_END
            .apply()
    }
}
