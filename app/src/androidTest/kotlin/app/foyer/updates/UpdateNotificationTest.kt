package app.foyer.updates

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.R
import org.junit.Assert.*
import org.junit.Test

class UpdateNotificationTest {
    @Test fun dailyWorkIsUniqueAndDisablingCancelsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = androidx.work.WorkManager.getInstance(context)
        try {
            UpdateWorker.schedule(context, true)
            UpdateWorker.schedule(context, true)
            val deadline = System.currentTimeMillis() + 5000
            var work = manager.getWorkInfosForUniqueWork("release-updates").get()
            while (work.none { !it.state.isFinished } && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                work = manager.getWorkInfosForUniqueWork("release-updates").get()
            }
            val active = work.filter { !it.state.isFinished }
            assertEquals(1, active.size)
            assertEquals(java.util.concurrent.TimeUnit.HOURS.toMillis(24), active.single().periodicityInfo!!.repeatIntervalMillis)
            assertEquals(androidx.work.NetworkType.CONNECTED, active.single().constraints.requiredNetworkType)
        } finally {
            UpdateWorker.schedule(context, false)
            manager.cancelUniqueWork("release-updates").result.get()
        }
        assertTrue(manager.getWorkInfosForUniqueWork("release-updates").get().all { it.state.isFinished })
    }

    @Test fun notificationOffersDownloadAndSkipAndSkipPersistsVersion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val preferences = context.updatePreferences()
        val previous = preferences.getString("skipped", null)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            notifyUpdate(context, Release("99.0.0", "$REPOSITORY/releases/download/v99.0.0/foyer-99.0.0.apk"))
            val postingDeadline = System.currentTimeMillis() + 5000
            while (manager.activeNotifications.none { it.id == UPDATE_NOTIFICATION } && System.currentTimeMillis() < postingDeadline) {
                Thread.sleep(50)
            }
            val notification = manager.activeNotifications.single { it.id == UPDATE_NOTIFICATION }.notification
            assertEquals(context.getString(R.string.updates_update), notification.actions[0].title)
            assertEquals(context.getString(R.string.updates_skip), notification.actions[1].title)
            // Execute only Skip. Never launch the release APK from an agent test.
            notification.actions[1].actionIntent.send()
            val deadline = System.currentTimeMillis() + 5000
            while (preferences.getString("skipped", null) != "99.0.0" && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals("99.0.0", preferences.getString("skipped", null))
            assertTrue(manager.activeNotifications.none { it.id == UPDATE_NOTIFICATION })
            assertTrue(context.updateNotificationsEnabled())
        } finally {
            preferences.edit().putString("skipped", previous).commit()
            manager.cancel(UPDATE_NOTIFICATION)
            manager.deleteNotificationChannel("updates")
        }
    }
}
