package com.kavach.app.isolation

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager

/**
 * Read/write access to the isolation plane.
 *
 * Everything here is a thin, defensive wrapper over [DevicePolicyManager]. Device
 * administration APIs throw [SecurityException] the moment Kavach is not the profile
 * owner, and several OEM builds diverge from AOSP behaviour, so every call is
 * guarded. A failure to apply a hardening step is reported honestly rather than
 * swallowed, because a user who thinks the boundary is sealed when it is not is
 * worse off than one who knows it failed.
 */
class IsolationManager(context: Context) {

    private val appContext = context.applicationContext
    private val dpm =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = KavachDeviceAdminReceiver.componentName(appContext)

    /** True when this Kavach install is the profile owner of a managed profile. */
    val isProfileOwner: Boolean
        get() = runCatching { dpm.isProfileOwnerApp(appContext.packageName) }.getOrDefault(false)

    /** True when the *running* copy of Kavach is the one inside the work profile. */
    val isInsideManagedProfile: Boolean
        get() = isProfileOwner

    /**
     * Whether this device can host a managed profile at all.
     *
     * Returns false on devices that already have a profile, devices where the OEM
     * disabled the feature, and secondary users.
     */
    fun canProvision(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = provisioningIntent()
            dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE) &&
                intent.resolveActivity(appContext.packageManager) != null
        } else {
            dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        }
    }.getOrDefault(false)

    /**
     * The system intent that creates the profile.
     *
     * Provisioning is driven entirely by the OS: it shows its own consent screens,
     * creates the user, and installs Kavach into it. Kavach cannot create a profile
     * silently, and that is a feature - the user is always told what is happening.
     */
    fun provisioningIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, false)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_USER_CONSENT, false)
        }

    /** Current state of the two restrictions that actually seal the boundary. */
    fun boundaryState(): BoundaryState {
        if (!isProfileOwner) return BoundaryState(managed = false)
        val restrictions = runCatching { dpm.getUserRestrictions(admin) }.getOrNull()
        return BoundaryState(
            managed = true,
            sharingBlocked = restrictions
                ?.getBoolean(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE) ?: false,
            clipboardBlocked = restrictions
                ?.getBoolean(UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE) ?: false,
        )
    }

    fun sealBoundary(): Result<Unit> = runCatching {
        require(isProfileOwner) { "Kavach is not the profile owner" }
        KavachDeviceAdminReceiver.hardenCrossProfileBoundary(dpm, admin)
    }

    fun openBoundary(): Result<Unit> = runCatching {
        require(isProfileOwner) { "Kavach is not the profile owner" }
        KavachDeviceAdminReceiver.relaxCrossProfileBoundary(dpm, admin)
    }

    /**
     * Permanently removes the work profile and everything installed in it.
     *
     * Irreversible, and the UI states that plainly before calling this.
     */
    fun removeProfile(): Result<Unit> = runCatching {
        require(isProfileOwner) { "Kavach is not the profile owner" }
        dpm.wipeData(0)
    }

    /**
     * Revokes a runtime permission for an app in the profile and denies future prompts.
     *
     * Useful for the common case of a game or utility that asks for location purely so
     * its advertising SDK can sell it.
     */
    fun denyPermission(packageName: String, permission: String): Result<Unit> = runCatching {
        require(isProfileOwner) { "Kavach is not the profile owner" }
        val ok = dpm.setPermissionGrantState(
            admin,
            packageName,
            permission,
            DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED,
        )
        check(ok) { "System refused to change the grant state for $packageName" }
    }

    fun resetPermission(packageName: String, permission: String): Result<Unit> = runCatching {
        require(isProfileOwner) { "Kavach is not the profile owner" }
        dpm.setPermissionGrantState(
            admin,
            packageName,
            permission,
            DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
        )
        Unit
    }

    /** Hides an app inside the profile without uninstalling it. */
    fun setHidden(packageName: String, hidden: Boolean): Result<Boolean> = runCatching {
        require(isProfileOwner) { "Kavach is not the profile owner" }
        dpm.setApplicationHidden(admin, packageName, hidden)
    }

    data class BoundaryState(
        val managed: Boolean,
        val sharingBlocked: Boolean = false,
        val clipboardBlocked: Boolean = false,
    ) {
        val fullySealed: Boolean get() = managed && sharingBlocked && clipboardBlocked
    }
}
