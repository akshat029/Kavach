package com.kavach.app.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.kavach.app.KavachApp
import com.kavach.app.R
import com.kavach.app.core.model.NetworkMode
import com.kavach.app.core.policy.PolicyEngine
import com.kavach.app.core.policy.PolicySnapshot
import com.kavach.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The filtering tunnel.
 *
 * ## Why this design
 *
 * A conventional per-app firewall installs a default route and then has to
 * re-implement TCP and UDP in userspace to forward everything it allows. That is
 * thousands of lines of state machine, it halves throughput, it drains battery,
 * and every bug in it looks to the user like "the internet is broken".
 *
 * Kavach instead routes **only its own virtual DNS address** into the tunnel.
 * Consequences:
 *
 *  - Every DNS question on the device is seen and can be refused.
 *  - Every other packet never enters the tunnel at all, so throughput, battery
 *    and app compatibility are untouched.
 *  - There is no connection state, so there is no stack to get wrong.
 *
 * The honest limit of this approach is written down in ARCHITECTURE.md: an app
 * that connects to a hard-coded IP address never asks a question, so it is never
 * refused. Closing that gap needs a userspace transport and is Phase 2 work.
 */
class KavachVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tunnel: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private var forwarder: ExecutorService? = null
    private var resolver: DohResolver? = null

    private lateinit var uidResolver: UidResolver
    private lateinit var logger: ConnectionLogger

    /** Read without locking by every forwarder thread; replaced wholesale on change. */
    @Volatile
    private var snapshot: PolicySnapshot = PolicySnapshot.EMPTY

    /** Serialises writes back into the single TUN file descriptor. */
    private val writeLock = Any()

    override fun onCreate() {
        super.onCreate()
        uidResolver = UidResolver(this)
        logger = ConnectionLogger(app.database.dao(), scope)

        scope.launch {
            app.policyRepository.snapshot.collect { latest ->
                val restartNeeded = requiresRestart(snapshot, latest)
                snapshot = latest
                logger.enabled = latest.loggingEnabled
                // App membership is fixed at establish() time, so a change to which
                // apps are excluded can only be applied by rebuilding the interface.
                if (restartNeeded && tunnel != null) restartTunnel()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                startForegroundNotification()
                restartTunnel()
                return START_STICKY
            }

            else -> {
                startForegroundNotification()
                if (tunnel == null) startTunnel()
                return START_STICKY
            }
        }
    }

    /** Another VPN app took over, or the user revoked consent. */
    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tunnel

    private fun startTunnel() {
        val descriptor = try {
            establish(snapshot)
        } catch (t: Throwable) {
            null
        }

        if (descriptor == null) {
            // establish() returns null when the user revoked VPN consent between
            // the prepare() dialog and here. Shut down quietly rather than looping.
            running.value = false
            stopSelf()
            return
        }

        tunnel = descriptor
        resolver = DohResolver(this, DohEndpoints.byId(snapshot.dohEndpointId))
        forwarder = buildForwarderPool()
        logger.start()
        running.value = true

        tunnelThread = Thread({ runTunnel(descriptor) }, "kavach-tun").apply {
            isDaemon = true
            start()
        }
    }

    private fun restartTunnel() {
        stopTunnel(keepServiceAlive = true)
        startTunnel()
    }

    private fun stopTunnel(keepServiceAlive: Boolean = false) {
        running.value = false

        // Closing the descriptor is what unblocks the reader thread's read() call.
        try {
            tunnel?.close()
        } catch (_: IOException) {
            // Already closed.
        }
        tunnel = null

        tunnelThread?.interrupt()
        tunnelThread = null

        forwarder?.let { pool ->
            pool.shutdownNow()
            try {
                pool.awaitTermination(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        forwarder = null

        resolver?.shutdown()
        resolver = null

        logger.stop()

        if (!keepServiceAlive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun establish(current: PolicySnapshot): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(V4_CLIENT, V4_PREFIX)
            .addDnsServer(V4_DNS)
            .addRoute(V4_DNS, V4_PREFIX)

        // IPv6 is configured too, otherwise an IPv6-only network would bypass
        // filtering entirely - which is a silent failure, the worst kind.
        try {
            builder.addAddress(V6_CLIENT, V6_PREFIX)
                .addDnsServer(V6_DNS)
                .addRoute(V6_DNS, V6_PREFIX)
        } catch (_: IllegalArgumentException) {
            // Device without IPv6 support in the VPN stack; v4 filtering still applies.
        }

        // Kavach must never filter itself: the DoH resolver lives in this process.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Throwable) {
            // Cannot happen for our own package, but never let it stop the tunnel.
        }

        // Apps the user marked Unfiltered leave the tunnel completely, so Kavach
        // genuinely does not touch them rather than merely allowing their queries.
        for (packageName in bypassedPackages(current)) {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Throwable) {
                // Uninstalled between snapshot and establish. Skip it.
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        builder.setConfigureIntent(configureIntent())

        return builder.establish()
    }

    private fun bypassedPackages(current: PolicySnapshot): List<String> =
        current.policies.values
            .filter { it.networkMode == NetworkMode.ALLOW_ALL }
            .map { it.packageName }

    /** Only a change to tunnel *membership* needs the interface rebuilt. */
    private fun requiresRestart(old: PolicySnapshot, new: PolicySnapshot): Boolean {
        if (old === PolicySnapshot.EMPTY) return false
        if (old.dohEndpointId != new.dohEndpointId) return true
        return bypassedPackages(old).toSet() != bypassedPackages(new).toSet()
    }

    private fun buildForwarderPool(): ExecutorService = ThreadPoolExecutor(
        2,
        FORWARDER_MAX_THREADS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(FORWARDER_QUEUE_DEPTH),
        // Shedding load is correct here: a dropped query makes the client retry,
        // whereas an unbounded queue would make every app hang together.
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    // -------------------------------------------------------------- read loop

    private fun runTunnel(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)

        try {
            while (!Thread.currentThread().isInterrupted) {
                val read = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    break // Descriptor closed by stopTunnel(). Normal shutdown.
                }
                if (read < 0) break
                if (read == 0) continue
                dispatch(buffer, read, output)
            }
        } finally {
            try {
                input.close()
            } catch (_: IOException) {
                // Ignored during teardown.
            }
            try {
                output.close()
            } catch (_: IOException) {
                // Ignored during teardown.
            }
        }
    }

    private fun dispatch(buffer: ByteArray, length: Int, output: FileOutputStream) {
        // Only the virtual DNS address is routed here, so anything that is not a
        // UDP question is a stray packet and is dropped on the floor.
        if (Packets.protocolOf(buffer, length) != Packets.PROTO_UDP) return

        // parseUdp copies out of the shared read buffer, so the result is safe to
        // hand to another thread while the reader immediately reuses `buffer`.
        val datagram = Packets.parseUdp(buffer, length) ?: return
        if (datagram.destinationPort != DNS_PORT) return
        if (datagram.payload.size < DnsMessage.HEADER_LEN) return

        try {
            forwarder?.execute { handleQuery(datagram, output) }
        } catch (_: RejectedExecutionException) {
            // Pool shutting down mid-flight.
        }
    }

    private fun handleQuery(datagram: Packets.UdpDatagram, output: FileOutputStream) {
        val query = datagram.payload
        if (!DnsMessage.isQuery(query)) return
        val question = DnsMessage.parseQuestion(query) ?: return

        val uid = uidResolver.uidFor(
            Packets.PROTO_UDP,
            InetSocketAddress(datagram.sourceAddress, datagram.sourcePort),
            InetSocketAddress(datagram.destinationAddress, datagram.destinationPort),
        )
        val owner = uidResolver.packageFor(uid)

        val verdict = PolicyEngine.evaluate(snapshot, owner, question.name)
        logger.record(owner, uid, question, verdict)

        val reply = if (!verdict.allowed) {
            DnsMessage.buildSinkhole(query, question)
        } else {
            resolver?.resolve(query)
                // No plaintext fallback by design: see DohResolver.
                ?: DnsMessage.buildEmptyResponse(query, question, DnsMessage.RCODE_SERVFAIL)
        }

        val safeReply = if (reply.size > MAX_DNS_PAYLOAD) {
            DnsMessage.buildTruncated(query, question)
        } else {
            reply
        }

        writeReply(datagram, safeReply, output)
    }

    private fun writeReply(
        request: Packets.UdpDatagram,
        payload: ByteArray,
        output: FileOutputStream,
    ) {
        val packet = Packets.buildUdpReply(request, payload) ?: return
        synchronized(writeLock) {
            try {
                output.write(packet)
                output.flush()
            } catch (_: IOException) {
                // Tunnel torn down while a query was in flight.
            }
        }
    }

    // ---------------------------------------------------------- notification

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KavachVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, KavachApp.CHANNEL_TUNNEL)
            .setContentTitle(getString(R.string.tunnel_active))
            .setContentText(getString(R.string.notif_channel_tunnel_desc))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(configureIntent())
            .addAction(0, getString(R.string.tunnel_stop), stopIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun configureIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private val app: KavachApp get() = application as KavachApp

    companion object {
        const val ACTION_START = "com.kavach.app.action.START"
        const val ACTION_STOP = "com.kavach.app.action.STOP"
        const val ACTION_RESTART = "com.kavach.app.action.RESTART"

        private const val NOTIFICATION_ID = 0x4B41 // 'KA'
        private const val DNS_PORT = 53
        private const val MTU = 1500

        /** MTU minus the largest IP header (IPv6, 40) and the UDP header (8). */
        private const val MAX_DNS_PAYLOAD = MTU - 48

        private const val FORWARDER_MAX_THREADS = 12
        private const val FORWARDER_QUEUE_DEPTH = 256

        // RFC 5737 / RFC 4193 documentation ranges: guaranteed never to collide
        // with a real destination the user might need to reach.
        private const val V4_CLIENT = "198.18.71.1"
        private const val V4_DNS = "198.18.71.53"
        private const val V4_PREFIX = 32
        private const val V6_CLIENT = "fd6b:6176:6163:68::1"
        private const val V6_DNS = "fd6b:6176:6163:68::53"
        private const val V6_PREFIX = 128

        private val running = MutableStateFlow(false)

        /** Observed by the UI so the shield toggle always reflects reality. */
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, KavachVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, KavachVpnService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                // Service already gone.
            }
        }
    }
}
