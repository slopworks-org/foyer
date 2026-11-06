package app.foyer.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import app.foyer.R
import app.foyer.graph
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal const val UPDATE_NOTIFICATION = 1001
internal fun Context.updatePreferences() = getSharedPreferences("updates", Context.MODE_PRIVATE)
internal fun Context.installedVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"

class UpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (context.graph.layout.layout.value.settings.updateChecks != true) return Result.success()
        return try {
            val release = latestRelease(context.installedVersion())
            if (release != null && context.graph.layout.layout.value.settings.updateChecks == true &&
                context.updatePreferences().getString("skipped", null) != release.version) {
                notifyUpdate(context, release)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (enabled) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel("updates", context.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_DEFAULT),
                )
                manager.enqueueUniquePeriodicWork(
                    "release-updates", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build(),
                )
            } else {
                manager.cancelUniqueWork("release-updates")
                context.getSystemService(NotificationManager::class.java).cancel(UPDATE_NOTIFICATION)
            }
        }
    }
}

internal fun notifyUpdate(context: Context, release: Release) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("updates", context.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_DEFAULT))
    if (!context.updateNotificationsEnabled()) return
    val update = PendingIntent.getActivity(context, 0,
        Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val skip = PendingIntent.getBroadcast(context, 1,
        Intent(context, SkipUpdateReceiver::class.java).putExtra("version", release.version),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, "updates")
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(context.getString(R.string.updates_available, release.version))
        .setContentText(context.getString(R.string.updates_download_summary))
        .setContentIntent(update).setAutoCancel(true).setOnlyAlertOnce(true)
        .addAction(0, context.getString(R.string.updates_update), update)
        .addAction(0, context.getString(R.string.updates_skip), skip)
        .build()
    try {
        manager.notify(UPDATE_NOTIFICATION, notification)
    } catch (_: SecurityException) {
        // Permission can be revoked between the availability check and posting.
    }
}

class SkipUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getStringExtra("version") ?: return
        if (versionParts(version) == null) return
        context.updatePreferences().edit().putString("skipped", version).apply()
        context.getSystemService(NotificationManager::class.java).cancel(UPDATE_NOTIFICATION)
    }
}

internal fun Context.updateNotificationsEnabled(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        getSystemService(NotificationManager::class.java).getNotificationChannel("updates")?.importance != NotificationManager.IMPORTANCE_NONE
