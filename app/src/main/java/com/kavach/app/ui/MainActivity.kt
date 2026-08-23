package com.kavach.app.ui

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import com.kavach.app.ui.nav.KavachActions
import com.kavach.app.ui.nav.KavachRoot
import com.kavach.app.ui.theme.KavachTheme
import com.kavach.app.vpn.KavachVpnService

/**
 * The single activity.
 *
 * Its only real job is to own the three system dialogs Kavach cannot start without -
 * VPN consent, notification permission and work-profile provisioning - and to hand
 * the results down to Compose. Everything else lives in the screens.
 */
class MainActivity : ComponentActivity() {

    private lateinit var vpnConsent: ActivityResultLauncher<Intent>
    private lateinit var notificationPermission: ActivityResultLauncher<String>
    private lateinit var provisioning: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        vpnConsent = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                KavachVpnService.start(this)
            } else {
                toast("Kavach needs VPN permission to filter DNS. Nothing is sent to a server.")
            }
        }

        notificationPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                // The tunnel still runs; Android just shows a silent system entry
                // instead of ours. Say so rather than leaving the user confused.
                toast("Without notification access the shield still runs, but you won't see its status.")
            }
        }

        provisioning = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK) {
                toast("Work profile setup was cancelled.")
            }
        }

        setContent {
            KavachTheme {
                Root()
            }
        }
    }

    @Composable
    private fun Root() = KavachRoot(
        actions = KavachActions(
            startShield = ::startShield,
            stopShield = { KavachVpnService.stop(this) },
            requestProvisioning = ::requestProvisioning,
            openAppSettings = ::openAppSettings,
        )
    )

    /**
     * Asks for VPN consent, then starts the tunnel.
     *
     * [VpnService.prepare] returns null when consent already exists. Calling
     * startService without it throws, so this ordering is not optional.
     */
    private fun startShield() {
        ensureNotificationPermission()
        val consent = try {
            VpnService.prepare(this)
        } catch (_: Throwable) {
            // Some heavily modified builds have no VPN subsystem at all.
            toast("This device does not support VPN-based filtering.")
            return
        }

        if (consent == null) {
            KavachVpnService.start(this)
        } else {
            vpnConsent.launch(consent)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestProvisioning(intent: Intent) {
        try {
            provisioning.launch(intent)
        } catch (_: Throwable) {
            toast("This device cannot create a work profile.")
        }
    }

    private fun openAppSettings(packageName: String) {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            toast("Could not open system settings for this app.")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
