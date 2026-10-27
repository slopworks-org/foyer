package app.foyer

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.UserHandle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import app.foyer.data.AppInfo
import app.foyer.data.Edits
import app.foyer.data.WidgetContent
import app.foyer.system.DefaultHome
import app.foyer.system.Launch
import app.foyer.system.StatusBar
import app.foyer.system.widgetSpan
import app.foyer.ui.FoyerRoot
import app.foyer.ui.LauncherActions
import app.foyer.ui.LauncherState

class MainActivity : ComponentActivity(), LauncherActions {
    private val graph: Graph get() = (application as FoyerApp).graph
    private var state: LauncherState? = null

    /** Widget being added while the system asks for bind permission or the widget configures itself. */
    private var pending: PendingWidget? = null

    private class PendingWidget(val id: Int, val info: AppWidgetProviderInfo, val x: Int, val y: Int)

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val p = pending ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) configureOrPlace(p) else abandon(p)
    }

    private val requestHome = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state?.isDefaultHome = DefaultHome.isDefault(this)
        graph.apps.reload() // shortcuts become available once we are the home app
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { false },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { false },
        )
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val s = remember { LauncherState(graph, scope, this) }
            state = s
            FoyerRoot(s)
        }
    }

    override fun onStart() {
        super.onStart()
        graph.widgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()
        state?.isDefaultHome = DefaultHome.isDefault(this)
    }

    override fun onStop() {
        super.onStop()
        graph.widgetHost.stopListening()
        // Coming back home should show home, not a stale search.
        state?.let {
            it.dismissOverlays()
            if (it.searchOpen) it.closeSearch(animated = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        // Lifecycle is still RESUMED here if Home was pressed while we were already on screen.
        val wasVisible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            state?.onHomePressed(wasVisible)
        }
    }

    @Deprecated("Widget configuration still uses the classic result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        val p = pending ?: return
        if (resultCode == RESULT_OK) place(p) else abandon(p)
    }

    // --- LauncherActions ---

    override fun launchApp(app: AppInfo, bounds: Rect?) = Launch.app(this, app, bounds)

    override fun launchShortcut(pkg: String, id: String, user: UserHandle, bounds: Rect?) =
        Launch.shortcut(this, pkg, id, user, bounds)

    override fun appDetails(app: AppInfo) = Launch.appDetails(this, app)

    override fun uninstall(app: AppInfo) = Launch.uninstall(this, app)

    override fun wallpaper() = Launch.wallpaper(this)

    override fun requestDefaultHome() {
        requestHome.launch(DefaultHome.requestIntent(this))
    }

    override fun expandNotifications() = StatusBar.expandNotifications(this)

    override fun webSearch(query: String) = Launch.webSearch(this, query)

    override fun addWidget(info: AppWidgetProviderInfo, x: Int, y: Int) {
        val id = graph.widgetHost.allocateAppWidgetId()
        val p = PendingWidget(id, info, x, y)
        pending = p
        if (graph.widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrPlace(p)
        } else {
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
            )
        }
    }

    override fun reconfigureWidget(widgetId: Int) {
        try {
            graph.widgetHost.startAppWidgetConfigureActivityForResult(this, widgetId, 0, REQUEST_RECONFIGURE, null)
        } catch (e: Exception) {
            android.util.Log.w("Foyer", "Cannot reconfigure widget $widgetId", e)
        }
    }

    private fun configureOrPlace(p: PendingWidget) {
        val optional = p.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (p.info.configure != null && !optional) {
            try {
                graph.widgetHost.startAppWidgetConfigureActivityForResult(this, p.id, 0, REQUEST_CONFIGURE, null)
                return
            } catch (e: Exception) {
                android.util.Log.w("Foyer", "Cannot configure widget", e)
                abandon(p)
                return
            }
        }
        place(p)
    }

    private fun place(p: PendingWidget) {
        pending = null
        val (cw, ch) = graph.cellSizePx
        val span = widgetSpan(p.info, cw, ch)
        graph.layout.edit { l ->
            // Where the user long-pressed, if it fits; otherwise the first spot that does.
            val x = p.x.coerceAtMost(app.foyer.data.HomeLayout.COLUMNS - span.w)
            val (fx, fy) = if (l.isFree(x, p.y, span.w, span.h)) x to p.y else l.firstFree(span.w, span.h, fromY = p.y)
            Edits.add(l, WidgetContent(p.id, p.info.provider.flattenToString()), fx, fy, span.w, span.h)
        }
    }

    private fun abandon(p: PendingWidget) {
        pending = null
        graph.widgetHost.deleteAppWidgetId(p.id)
    }

    private companion object {
        const val REQUEST_CONFIGURE = 11
        const val REQUEST_RECONFIGURE = 12
    }
}
