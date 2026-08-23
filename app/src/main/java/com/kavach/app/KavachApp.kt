package com.kavach.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.kavach.app.core.blocklist.BlocklistRepository
import com.kavach.app.core.blocklist.BlocklistWorker
import com.kavach.app.data.db.KavachDatabase
import com.kavach.app.data.repo.PolicyRepository
import com.kavach.app.data.repo.SettingsRepository
import com.kavach.app.isolation.IsolationManager
import com.kavach.app.util.AppLister
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application container.
 *
 * Dependencies are plain lazy singletons rather than a DI framework. For a project
 * this size a graph library would add build time, an annotation processor and a
 * layer of indirection without removing a single line of real work. Everything is
 * constructed here, and everything is reachable as `(application as KavachApp)`.
 */
class KavachApp : Application() {

    /** Lives as long as the process; used for work that must outlive any screen. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: KavachDatabase by lazy { KavachDatabase.get(this) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val blocklistRepository: BlocklistRepository by lazy { BlocklistRepository(this) }

    val appLister: AppLister by lazy { AppLister(this) }

    val isolationManager: IsolationManager by lazy { IsolationManager(this) }

    val policyRepository: PolicyRepository by lazy {
        PolicyRepository(
            dao = database.dao(),
            settings = settingsRepository,
            blocklists = blocklistRepository,
            scope = applicationScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        applicationScope.launch {
            // Compile the bundled lists immediately so the very first query after a
            // cold install is already filtered.
            blocklistRepository.load()
        }

        BlocklistWorker.schedule(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tunnel = NotificationChannel(
            CHANNEL_TUNNEL,
            getString(R.string.notif_channel_tunnel_name),
            // MIN keeps the mandatory foreground-service notification out of the way
            // without hiding it, which Android does not allow anyway.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notif_channel_tunnel_desc)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.notif_channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notif_channel_alerts_desc)
            setShowBadge(true)
        }

        manager.createNotificationChannels(listOf(tunnel, alerts))
    }

    companion object {
        const val CHANNEL_TUNNEL = "kavach_tunnel"
        const val CHANNEL_ALERTS = "kavach_alerts"
    }
}
