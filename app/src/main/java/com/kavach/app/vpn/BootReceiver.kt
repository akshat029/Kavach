package com.kavach.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.kavach.app.KavachApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts the tunnel after a reboot or a self-update, but only if the user asked
 * for that and only if VPN consent is still granted.
 *
 * [VpnService.prepare] returning non-null means consent was revoked while we were
 * not running. Starting anyway would throw, so we stay down and let the user
 * re-enable from the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val app = context.applicationContext as? KavachApp ?: return
        val pending = goAsync()

        app.applicationScope.launch(Dispatchers.IO) {
            try {
                val settings = app.settingsRepository.current()
                if (!settings.autoStart) return@launch
                if (VpnService.prepare(context) != null) return@launch
                KavachVpnService.start(context)
            } catch (_: Throwable) {
                // Never crash on boot: a broadcast receiver crash loop is the fastest
                // way to get an app force-stopped by the system.
            } finally {
                pending.finish()
            }
        }
    }
}
