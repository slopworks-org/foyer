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

@Composable
internal fun UpdateConsentBanner(onChoice: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(MenuIcons.Info, contentDescription = null, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.updates_question), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.updates_consent_summary), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onChoice(false) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text(stringResource(R.string.updates_no))
                }
                Button(onClick = { onChoice(true) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.updates_yes))
                }
            }
        }
    }
}

/** Navigation and automatic checking are independent touch and accessibility targets. */
@Composable
internal fun UpdatePreference(
    enabled: Boolean, onOpen: () -> Unit, onEnabledChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val switchLabel = stringResource(R.string.updates_automatic)
    Surface(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        contentColor = colors.onSurface,
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen)
                    .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.updates_title), fontSize = 16.sp)
                    Text(stringResource(R.string.updates_preference_summary),
                        fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                Icon(MenuIcons.ChevronRight, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
            }
            VerticalDivider(Modifier.padding(vertical = 16.dp).fillMaxHeight())
            Box(
                Modifier.width(84.dp).fillMaxHeight().semantics { contentDescription = switchLabel }
                    .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange),
                contentAlignment = Alignment.Center,
            ) {
                Switch(enabled, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

@Composable
internal fun UpdateSettingsContent(
    enabled: Boolean, state: UpdateUiState,
    onChoice: (Boolean) -> Unit, onCheck: () -> Unit, onUpdate: () -> Unit, onNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = colors.primary,
            contentColor = colors.onPrimary,
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = enabled, role = Role.Switch, onValueChange = onChoice)
                    .heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.updates_automatic), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = enabled, onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics { },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.primary,
                        checkedTrackColor = colors.onPrimary,
                        checkedBorderColor = colors.onPrimary,
                        checkedIconColor = colors.onPrimary,
                    ),
                    thumbContent = if (enabled) {
                        { Icon(MenuIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
        if (!state.notifications) {
            Surface(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer,
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(shape = androidx.compose.foundation.shape.CircleShape,
                            color = colors.secondary, contentColor = colors.onSecondary) {
                            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                Icon(MenuIcons.Notifications, contentDescription = null, modifier = Modifier.size(32.dp))
                            }
                        }
                        Text(stringResource(R.string.updates_notifications_title), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge)
                    }
                    Text(stringResource(R.string.updates_notifications_disabled), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSecondaryContainer)) {
                        Text(stringResource(R.string.updates_notifications_action))
                    }
                }
            }
        }

        Text(stringResource(R.string.updates_daily), Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Surface(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainer, contentColor = colors.onSurface,
        ) {
            Column {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val failed = state.message == R.string.updates_error
                    val title = when {
                        state.busy -> stringResource(R.string.updates_checking_title)
                        state.version != null -> stringResource(R.string.updates_available, state.version)
                        failed -> stringResource(R.string.updates_error_title)
                        state.message == R.string.updates_current -> stringResource(R.string.updates_current)
                        else -> stringResource(R.string.updates_idle_title)
                    }
                    Column(
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            when {
                                state.busy -> MenuIcons.Search
                                state.version != null -> MenuIcons.Download
                                failed -> MenuIcons.Info
                                state.message == R.string.updates_current -> MenuIcons.Check
                                else -> MenuIcons.Search
                            },
                            contentDescription = null, modifier = Modifier.size(32.dp),
                            tint = if (failed && state.version == null) colors.error else colors.primary,
                        )
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.updates_installed, state.installed),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.version != null) {
                            Text(stringResource(R.string.updates_previous_result, state.version),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                    if (state.version != null) {
                        Text(stringResource(R.string.updates_download_summary),
                            style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    } else if (!state.busy && state.message == 0) {
                        Text(stringResource(R.string.updates_idle_summary),
                            style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                }
                if (state.message != 0 && state.message != R.string.updates_current) {
                    Surface(color = colors.errorContainer, contentColor = colors.onErrorContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(MenuIcons.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                            Text(stringResource(if (state.version != null && state.message == R.string.updates_error)
                                R.string.updates_recheck_error else state.message),
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Column(Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.version != null) {
                        Button(onClick = onUpdate, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.updates_download))
                        }
                    }
                    val checkLabel = stringResource(if (state.busy) R.string.updates_checking else R.string.updates_check)
                    if (state.version == null) {
                        Button(onClick = onCheck, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(checkLabel) }
                    } else {
                        OutlinedButton(onClick = onCheck, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(checkLabel) }
                    }
                }
            }
        }
    }
}
