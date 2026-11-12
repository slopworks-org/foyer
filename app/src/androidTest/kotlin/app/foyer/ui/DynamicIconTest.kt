package app.foyer.ui

import android.content.pm.LauncherApps
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Process
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.data.DynamicIcon
import app.foyer.data.Icon
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Uses the actual Google app resources installed on the selected emulator. */
class DynamicIconTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun load(pkg: String): DynamicIcon {
        val activities = context.getSystemService(LauncherApps::class.java).getActivityList(pkg, Process.myUserHandle())
        assumeTrue("Install $pkg to verify its dynamic resources", activities.isNotEmpty())
        val info = activities.first()
        return requireNotNull(DynamicIcon.load(context, info) { drawable ->
            fun bitmap(d: android.graphics.drawable.Drawable) = createBitmap(144, 144).also {
                d.setBounds(0, 0, 144, 144)
                d.draw(Canvas(it))
            }.asImageBitmap()
            val mono = if (Build.VERSION.SDK_INT >= 33) (drawable as? AdaptiveIconDrawable)?.monochrome else null
            Icon(bitmap(drawable), mono?.let { bitmap(it) })
        }) { "No dynamic metadata in $pkg" }
    }

    private fun time(day: Int = 1, hour: Int = 0, minute: Int = 0, second: Int = 0) =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JANUARY, day, hour, minute, second)
        }

    @Test fun googleCalendarRendersEveryDayAndRefreshesAcrossMidnight() {
        val dynamic = load("com.google.android.calendar")
        val frames = (1..31).map { dynamic.at(time(day = it)).full.asAndroidBitmap() }
        for (a in frames.indices) for (b in 0 until a) assertFalse("Days ${a + 1} and ${b + 1}", frames[a].sameAs(frames[b]))
        val before = dynamic.at(time(day = 31, hour = 23, minute = 59, second = 59))
        val after = dynamic.at(time(day = 1))
        assertFalse(before.full.asAndroidBitmap().sameAs(after.full.asAndroidBitmap()))
        if (before.mono != null && after.mono != null) {
            assertFalse(before.mono.asAndroidBitmap().sameAs(after.mono.asAndroidBitmap()))
        }
    }

    @Test fun googleClockChangesHandsInColorAndMonochrome() {
        val dynamic = load("com.google.android.deskclock")
        val a = dynamic.at(time(hour = 3, minute = 15))
        val b = dynamic.at(time(hour = 8, minute = 40))
        assertFalse(a.full.asAndroidBitmap().sameAs(b.full.asAndroidBitmap()))
        assertNotNull(a.mono)
        assertFalse(a.mono!!.asAndroidBitmap().sameAs(b.mono!!.asAndroidBitmap()))
        assertTrue(a.full.asAndroidBitmap().sameAs(dynamic.at(time(hour = 3, minute = 15)).full.asAndroidBitmap()))
    }

    @Test fun visibleClockActuallyTicksInBothIconStyles() {
        val dynamic = load("com.google.android.deskclock")
        val icon = dynamic.at(Calendar.getInstance()).also { it.dynamic = dynamic }
        rule.setContent {
            Row {
                AppIcon(icon, 72.dp, Modifier.testTag("color"), themed = false)
                AppIcon(icon, 72.dp, Modifier.testTag("mono"), themed = true)
            }
        }
        val color = rule.onNodeWithTag("color").captureToImage().asAndroidBitmap()
        val mono = rule.onNodeWithTag("mono").captureToImage().asAndroidBitmap()
        rule.waitUntil(timeoutMillis = 65_000) {
            !color.sameAs(rule.onNodeWithTag("color").captureToImage().asAndroidBitmap()) &&
                !mono.sameAs(rule.onNodeWithTag("mono").captureToImage().asAndroidBitmap())
        }
    }
}
