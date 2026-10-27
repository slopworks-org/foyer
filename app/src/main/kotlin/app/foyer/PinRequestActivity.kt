package app.foyer

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import app.foyer.data.Edits
import app.foyer.data.ShortcutContent
import app.foyer.data.WidgetContent
import app.foyer.system.widgetSpan

/**
 * "Add to home screen" requests from other apps. Accepted straight away and put into the
 * first free spot from the top; the user moves it wherever they like afterwards.
 */
class PinRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = launcherApps.getPinItemRequest(intent)
        if (request == null || !request.isValid) {
            finish()
            return
        }
        val graph = graph
        when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val info = request.shortcutInfo ?: return finish()
                if (request.accept()) {
                    val label = (info.shortLabel ?: info.longLabel ?: "").toString()
                    val user = graph.apps.serialOf(info.userHandle)
                    graph.layout.edit { Edits.addFirstFree(it, ShortcutContent(info.`package`, info.id, user, label)) }
                    Toast.makeText(this, getString(R.string.pinned_to_home, label), Toast.LENGTH_SHORT).show()
                }
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                val info = request.getAppWidgetProviderInfo(this) ?: return finish()
                val id = graph.widgetHost.allocateAppWidgetId()
                if (graph.widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) &&
                    request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })
                ) {
                    val (cw, ch) = graph.cellSizePx
                    val span = widgetSpan(info, cw, ch)
                    graph.layout.edit { Edits.addFirstFree(it, WidgetContent(id, info.provider.flattenToString()), span.w, span.h) }
                } else {
                    graph.widgetHost.deleteAppWidgetId(id)
                }
            }
        }
        finish()
    }
}
