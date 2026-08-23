package com.kavach.app.isolation

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.kavach.app.R

/**
 * Profile owner for the Kavach work profile.
 *
 * Kavach does **not** implement its own app sandbox. Android already runs every app
 * under a distinct Linux UID with SELinux enforcement, and the app-level
 * virtualisation approach - loading other apps into your own process - would replace
 * that kernel-enforced boundary with hand-written hooks under a single shared UID.
 * That is strictly weaker than what the OS already gives you.
 *
 * Instead Kavach provisions a managed profile, which is a second kernel-enforced
 * user with its own data directories, its own clipboard, its own account set and no
 * default visibility into the personal side. Apps installed there genuinely cannot
 * read personal-profile data, because the kernel refuses - not because Kavach asked
 * them not to.
 */
class KavachDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)

        val dpm = getManager(context)
        val admin = getWho(context)

        // Name the profile so it is obvious in Settings which apps live where.
        dpm.setProfileName(admin, context.getString(R.string.isolation_profile_name))

        hardenCrossProfileBoundary(dpm, admin)

        // A managed profile stays disabled until the owner enables it.
        dpm.setProfileEnabled(admin)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.isolation_disable_warning)

    companion object {

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, KavachDeviceAdminReceiver::class.java)

        /**
         * Closes the cross-profile paths that are open by default.
         *
         * This is the part that is easy to get wrong. Clearing cross-profile intent
         * filters is not enough on its own: the system installs its own default
         * filters during provisioning that `clearCrossProfileIntentFilters` does not
         * touch. The share and copy-paste restrictions below are what actually close
         * those, and without them a user could still hand data across the boundary
         * through the share sheet while believing the profile was sealed.
         */
        fun hardenCrossProfileBoundary(dpm: DevicePolicyManager, admin: ComponentName) {
            runCatching { dpm.clearCrossProfileIntentFilters(admin) }

            val restrictions = listOf(
                UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE,
                UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE,
            )
            for (restriction in restrictions) {
                runCatching { dpm.addUserRestriction(admin, restriction) }
            }
        }

        fun relaxCrossProfileBoundary(dpm: DevicePolicyManager, admin: ComponentName) {
            val restrictions = listOf(
                UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE,
                UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE,
            )
            for (restriction in restrictions) {
                runCatching { dpm.clearUserRestriction(admin, restriction) }
            }
        }
    }
}
