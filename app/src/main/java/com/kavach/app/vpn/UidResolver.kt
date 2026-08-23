package com.kavach.app.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.LruCache
import java.io.File
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * Maps a packet back to the app that sent it.
 *
 * This is the single hardest part of any Android firewall, because Android has
 * removed the easy answers over time:
 *  - API 29+  : ConnectivityManager.getConnectionOwnerUid is the supported API.
 *  - API 26-28: /proc/net/{udp,udp6} is still readable by the app itself.
 *
 * When neither works the caller receives [INVALID_UID] and the policy engine fails
 * open. Guessing an owner would be worse than admitting we do not know.
 */
class UidResolver(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val packageManager: PackageManager = appContext.packageManager

    /** uid -> package name. Package installs are rare, so a small cache is plenty. */
    private val packageCache = LruCache<Int, String>(256)

    fun uidFor(protocol: Int, source: InetSocketAddress, destination: InetSocketAddress): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val uid = connectivityManager?.getConnectionOwnerUid(protocol, source, destination)
                    ?: INVALID_UID
                if (uid >= 0) return uid
            } catch (_: SecurityException) {
                // Some OEM builds refuse this call. Fall through to /proc.
            } catch (_: Throwable) {
                // Never let UID lookup take down the tunnel.
            }
        }
        return scanProcNet(protocol, source)
    }

    /**
     * Resolves a uid to a user-visible package.
     *
     * A shared uid can host several packages (`android.uid.system` and friends). We
     * return the first, which is stable for a given uid; shared uids are recorded as
     * a known limitation in ARCHITECTURE.md.
     */
    fun packageFor(uid: Int): String? {
        if (uid < 0) return null
        if (uid == Process.myUid()) return appContext.packageName
        packageCache.get(uid)?.let { return it }
        val packages = try {
            packageManager.getPackagesForUid(uid)
        } catch (_: Throwable) {
            null
        }
        val name = packages?.firstOrNull() ?: return null
        packageCache.put(uid, name)
        return name
    }

    fun invalidatePackageCache() = packageCache.evictAll()

    // ------------------------------------------------------------------- /proc

    private fun scanProcNet(protocol: Int, source: InetSocketAddress): Int {
        val files = when (protocol) {
            Packets.PROTO_UDP -> listOf("/proc/net/udp", "/proc/net/udp6")
            Packets.PROTO_TCP -> listOf("/proc/net/tcp", "/proc/net/tcp6")
            else -> return INVALID_UID
        }
        val address = source.address ?: return INVALID_UID
        val wantHex = encodeAddress(address.address, address is Inet6Address)

        for (path in files) {
            val uid = scanFile(path, wantHex, source.port)
            if (uid != INVALID_UID) return uid
        }
        return INVALID_UID
    }

    private fun scanFile(path: String, wantHex: String, wantPort: Int): Int {
        val file = File(path)
        if (!file.canRead()) return INVALID_UID
        return try {
            file.useLines { lines ->
                for (line in lines) {
                    // sl  local_address rem_address st tx_queue rx_queue tr when retrnsmt uid
                    val parts = line.trim().split(WHITESPACE)
                    if (parts.size < 8) continue
                    val local = parts[1]
                    val colon = local.indexOf(':')
                    if (colon <= 0) continue
                    val port = local.substring(colon + 1).toIntOrNull(16) ?: continue
                    if (port != wantPort) continue
                    val hex = local.substring(0, colon)
                    // A socket bound to the wildcard address still owns the flow.
                    if (!hex.equals(wantHex, ignoreCase = true) && hex.toLongOrNull(16) != 0L) {
                        continue
                    }
                    return@useLines parts[7].toIntOrNull() ?: INVALID_UID
                }
                INVALID_UID
            }
        } catch (_: Throwable) {
            INVALID_UID
        }
    }

    /**
     * /proc/net renders addresses as hex words in host byte order, which on every
     * Android device means little-endian within each 32-bit group.
     */
    private fun encodeAddress(bytes: ByteArray, ipv6: Boolean): String {
        val groups = if (ipv6) 4 else 1
        val sb = StringBuilder(bytes.size * 2)
        for (g in 0 until groups) {
            val base = g * 4
            if (base + 3 >= bytes.size) break
            for (i in 3 downTo 0) {
                sb.append(HEX[(bytes[base + i].toInt() shr 4) and 0x0F])
                sb.append(HEX[bytes[base + i].toInt() and 0x0F])
            }
        }
        return sb.toString()
    }

    companion object {
        const val INVALID_UID = -1
        private val WHITESPACE = Regex("\\s+")
        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
