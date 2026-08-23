package com.kavach.app.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.kavach.app.core.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enumerates installed apps for the Apps screen.
 *
 * Kavach needs QUERY_ALL_PACKAGES for this. The package list never leaves the
 * device: it is read here, rendered, and discarded.
 */
class AppLister(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    suspend fun installedApps(includeSystem: Boolean): List<AppInfo> = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_PERMISSIONS
        val packages = try {
            packageManager.getInstalledPackages(flags)
        } catch (_: Throwable) {
            emptyList()
        }

        packages.asSequence()
            .mapNotNull { pkg ->
                val applicationInfo = pkg.applicationInfo ?: return@mapNotNull null
                val isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

                if (isSystem && !includeSystem) return@mapNotNull null
                // Filtering ourselves would be meaningless: Kavach is excluded from
                // the tunnel by construction.
                if (pkg.packageName == appContext.packageName) return@mapNotNull null

                val hasInternet = pkg.requestedPermissions
                    ?.any { it == android.Manifest.permission.INTERNET } ?: false

                AppInfo(
                    packageName = pkg.packageName,
                    label = runCatching {
                        packageManager.getApplicationLabel(applicationInfo).toString()
                    }.getOrDefault(pkg.packageName),
                    uid = applicationInfo.uid,
                    isSystemApp = isSystem,
                    hasInternetPermission = hasInternet,
                    installedAt = pkg.firstInstallTime,
                )
            }
            .filter { it.hasInternetPermission }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Loaded lazily by the UI; never held in the app list model. */
    fun icon(packageName: String): Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Throwable) {
        null
    }
}
