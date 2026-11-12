package app.foyer.data

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import java.util.Calendar

/** Launcher3's calendar arrays and level-driven clock layers, with a static fallback. */
internal class DynamicIcon(
    val intervalMillis: Long,
    private val frame: (Calendar) -> Icon,
) {
    fun at(time: Calendar): Icon = frame(time)

    companion object {
        private const val CLOCK = "com.android.launcher3."

        fun load(context: Context, info: LauncherActivityInfo, render: (Drawable) -> Icon): DynamicIcon? = try {
            val pm = context.packageManager
            val app = context.getSystemService(android.content.pm.LauncherApps::class.java)
                .getApplicationInfo(info.componentName.packageName, PackageManager.GET_META_DATA, info.user)
            val resources = pm.getResourcesForApplication(app)
            val activity = pm.getActivityInfo(info.componentName, PackageManager.GET_META_DATA)
            from(resources, activity.metaData, app.metaData, app.packageName,
                context.resources.displayMetrics.densityDpi, render)
        } catch (_: Exception) {
            null
        }

        internal fun from(
            resources: Resources, activity: Bundle?, app: Bundle?, pkg: String, density: Int,
            render: (Drawable) -> Icon,
        ): DynamicIcon? {
            val array = activity?.getInt("$pkg.dynamic_icons", 0) ?: 0
            if (array != 0) {
                var day = -1
                var cached: Icon? = null
                return DynamicIcon(1_000) { time ->
                    val today = time.get(Calendar.DAY_OF_MONTH) - 1
                    if (today != day || cached == null) {
                        val icons = resources.obtainTypedArray(array)
                        val id = try { icons.getResourceId(today, 0) } finally { icons.recycle() }
                        cached = render(requireNotNull(resources.getDrawableForDensity(id, density, null)))
                        day = today
                    }
                    cached
                }
            }
            val id = app?.getInt("${CLOCK}LEVEL_PER_TICK_ICON_ROUND", 0) ?: 0
            if (id == 0) return null
            val icon = resources.getDrawableForDensity(id, density, null)?.mutate() as? AdaptiveIconDrawable ?: return null
            val foreground = icon.foreground as? LayerDrawable ?: return null
            val mono = if (Build.VERSION.SDK_INT >= 33) icon.monochrome as? LayerDrawable else null
            return clock(icon, foreground, mono, app!!, render)
        }

        internal fun clock(
            icon: Drawable, foreground: LayerDrawable, mono: LayerDrawable?, metadata: Bundle,
            render: (Drawable) -> Icon,
        ): DynamicIcon {
            fun update(layers: LayerDrawable, time: Calendar) {
                val hour = time.get(Calendar.HOUR)
                val minute = time.get(Calendar.MINUTE)
                val second = time.get(Calendar.SECOND)
                fun hand(name: String, level: Int) {
                    val index = metadata.getInt("${CLOCK}${name}_LAYER_INDEX", -1)
                    if (index in 0 until layers.numberOfLayers) layers.getDrawable(index).level = level
                }
                hand("HOUR", ((hour - metadata.getInt("${CLOCK}DEFAULT_HOUR") + 12) % 12) * 60 + minute)
                hand("MINUTE", hour * 60 + (minute - metadata.getInt("${CLOCK}DEFAULT_MINUTE") + 60) % 60)
                hand("SECOND", ((second - metadata.getInt("${CLOCK}DEFAULT_SECOND") + 60) % 60) * 10)
            }
            return DynamicIcon(1_000) { time ->
                update(foreground, time)
                mono?.let { update(it, time) }
                render(icon)
            }
        }
    }
}
