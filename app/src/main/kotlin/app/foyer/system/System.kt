package app.foyer.system

import android.util.SizeF
import android.os.Bundle
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast
import app.foyer.R
import app.foyer.data.AppInfo
import app.foyer.data.HomeLayout
import app.foyer.data.ShortcutEntry
import kotlin.math.ceil
import kotlin.math.hypot

object Launch {
    private fun options(activity: Activity, bounds: Rect?): android.os.Bundle? {
        if (bounds == null || bounds.isEmpty) return null
        return ActivityOptions.makeScaleUpAnimation(
            activity.window.decorView, bounds.left, bounds.top, bounds.width(), bounds.height(),
        ).toBundle()
    }

    fun app(activity: Activity, app: AppInfo, bounds: Rect?) {
        try {
            activity.getSystemService(LauncherApps::class.java)
                .startMainActivity(app.component, app.user, bounds, options(activity, bounds))
        } catch (e: Exception) {
            Log.w("Foyer", "Cannot start ${app.component}", e)
            Toast.makeText(activity, activity.getString(R.string.cannot_open_app, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    fun shortcut(activity: Activity, pkg: String, id: String, user: android.os.UserHandle, bounds: Rect?) {
        try {
            activity.getSystemService(LauncherApps::class.java)
                .startShortcut(pkg, id, bounds, options(activity, bounds), user)
        } catch (e: Exception) {
            Log.w("Foyer", "Cannot start shortcut $pkg/$id", e)
            Toast.makeText(activity, R.string.shortcut_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    fun shortcut(activity: Activity, s: ShortcutEntry, bounds: Rect?) = shortcut(activity, s.pkg, s.id, s.user, bounds)

    fun appDetails(activity: Activity, app: AppInfo) {
        activity.getSystemService(LauncherApps::class.java)
            .startAppDetailsActivity(app.component, app.user, null, null)
    }

    fun uninstall(activity: Activity, app: AppInfo) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.key.pkg, null))
            .putExtra(Intent.EXTRA_USER, app.user)
        safeStart(activity, intent)
    }

    fun wallpaper(activity: Activity) =
        safeStart(activity, Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), activity.getString(R.string.wallpaper_chooser)))

    /** A results page in the default browser, not the search app: ACTION_WEB_SEARCH would open Google's. */
    fun webSearch(activity: Activity, query: String) = safeStart(
        activity,
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query.trim())))
            .addCategory(Intent.CATEGORY_BROWSABLE),
    )

    fun safeStart(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }
}

object StatusBar {
    /** There is no public API for this; every third-party launcher uses the same hidden method. */
    @SuppressLint("WrongConstant")
    fun expandNotifications(context: Context) {
        try {
            val sbm = context.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (e: Exception) {
            Log.w("Foyer", "Cannot expand notifications", e)
        }
    }
}

object DefaultHome {
    fun isDefault(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    fun requestIntent(context: Context): Intent =
        context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME)
}

class FoyerWidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        FoyerWidgetHostView(context)

    companion object {
        const val HOST_ID = 0xF0E
    }
}

/**
 * Widgets swallow touches, so long-press is detected while intercepting, Launcher3-style.
 * Once it fires, the rest of the gesture is taken away from the widget: moving the finger
 * on drags the widget, reported in screen coordinates.
 */
class FoyerWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null

    /** No framework padding (8dp a side): like Pixel Launcher, the widget gets its whole cell area. */
    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        setPadding(0, 0, 0, 0)
    }

    /**
     * Tells the widget its real size. The framework subtracts its default padding from what it
     * reports even with the padding gone, so widgets laid themselves out 16dp too small.
     */
    fun reportSize(widthDp: Float, heightDp: Float) {
        val pad = getDefaultPaddingForWidget(context, null, null)
        val density = resources.displayMetrics.density
        val w = widthDp + (pad.left + pad.right) / density
        val h = heightDp + (pad.top + pad.bottom) / density
        updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
    }

    /** Drag after the long press: start (from where the finger went down), moves, then end or cancel. */
    var onDragStart: ((x: Float, y: Float) -> Unit)? = null
    var onDragMove: ((x: Float, y: Float) -> Unit)? = null
    var onDragEnd: ((completed: Boolean) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var longPressed = false
    private var dragging = false
    private val longPress = Runnable {
        longPressed = true
        // Keep the feed (and anything else up the tree) from taking the gesture over.
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            // Screen coordinates: while the feed scrolls, the widget moves along with the
            // finger, so in its own coordinates the finger barely moves and a slow scroll
            // used to turn into a long press (and then a drag).
            MotionEvent.ACTION_MOVE ->
                if (hypot(ev.rawX - downRawX, ev.rawY - downRawY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!longPressed) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            // Raw coordinates: the view itself moves with the finger.
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(ev.rawX - downRawX, ev.rawY - downRawY) > slop) {
                    dragging = true
                    onDragStart?.invoke(downRawX, downRawY)
                }
                if (dragging) onDragMove?.invoke(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd?.invoke(ev.actionMasked == MotionEvent.ACTION_UP)
                dragging = false
                longPressed = false
            }
        }
        return true
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPress)
    }
}

/** Widget size bounds in grid cells. */
data class WidgetSpan(val w: Int, val h: Int, val minW: Int, val minH: Int, val maxW: Int, val maxH: Int, val resizeH: Boolean, val resizeV: Boolean)

fun widgetSpan(info: AppWidgetProviderInfo, cellWidthPx: Float, cellHeightPx: Float): WidgetSpan {
    fun cells(px: Int, cell: Float, max: Int) = ceil(px / cell).toInt().coerceIn(1, max)
    val cols = HomeLayout.COLUMNS
    var w = if (info.targetCellWidth > 0) info.targetCellWidth.coerceIn(1, cols) else cells(info.minWidth, cellWidthPx, cols)
    var h = if (info.targetCellHeight > 0) info.targetCellHeight.coerceAtLeast(1) else cells(info.minHeight, cellHeightPx, 20)
    val minW = if (info.minResizeWidth > 0) cells(info.minResizeWidth, cellWidthPx, cols) else w
    val minH = if (info.minResizeHeight > 0) cells(info.minResizeHeight, cellHeightPx, 20) else h
    val maxW = if (info.maxResizeWidth > 0) cells(info.maxResizeWidth, cellWidthPx, cols) else cols
    val maxH = if (info.maxResizeHeight > 0) cells(info.maxResizeHeight, cellHeightPx, 20) else 20
    w = w.coerceIn(1, cols)
    h = h.coerceAtLeast(1)
    return WidgetSpan(
        w, h, minOf(minW, w), minOf(minH, h), maxOf(maxW, w), maxOf(maxH, h),
        resizeH = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
        resizeV = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
    )
}
