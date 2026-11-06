package app.foyer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import app.foyer.updates.updateNotificationsEnabled
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.foyer.R
import app.foyer.updates.Release
import app.foyer.updates.updatePreferences
import app.foyer.updates.installedVersion
import app.foyer.updates.latestRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class UpdateUiState(
    val installed: String, val busy: Boolean, val version: String?, val message: Int, val notifications: Boolean,
)

internal class UpdateActions(
    val choose: (Boolean) -> Unit, val check: () -> Unit, val download: () -> Unit, val notifications: () -> Unit,
)

/** Owns update work for the full settings session, independently of its current subpage. */
@Composable
internal fun UpdateSettings(
    onChoice: (Boolean) -> Unit,
    loadRelease: suspend (String) -> Release? = ::latestRelease,
    content: @Composable (UpdateUiState, UpdateActions) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<Release?>(null) }
    var message by remember { mutableIntStateOf(0) }
    var notifications by remember { mutableStateOf(context.updateNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = context.updateNotificationsEnabled()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notifications = context.updateNotificationsEnabled()
    }
    val installed = remember(context) { context.installedVersion() }
    val actions = UpdateActions(
        choose = {
            onChoice(it)
            val preferences = context.updatePreferences()
            if (it && Build.VERSION.SDK_INT >= 33 && !notifications && !preferences.getBoolean("permissionAsked", false)) {
                preferences.edit().putBoolean("permissionAsked", true).apply()
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        check = {
            if (!busy) {
                busy = true
                message = 0
                scope.launch {
                    try {
                        release = loadRelease(installed)
                        message = if (release == null) R.string.updates_current else 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        message = R.string.updates_error
                    } finally {
                        busy = false
                    }
                }
            }
        },
        download = {
            release?.let {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.downloadUrl)))
                } catch (_: ActivityNotFoundException) {
                    message = R.string.updates_no_browser
                }
            }
        },
        notifications = {
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "updates"))
        },
    )
    content(UpdateUiState(installed, busy, release?.version, message, notifications), actions)
}

