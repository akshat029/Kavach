package com.kavach.app.data.repo

import com.kavach.app.core.blocklist.BlocklistRepository
import com.kavach.app.core.model.NetworkMode
import com.kavach.app.core.model.Zone
import com.kavach.app.core.policy.AppPolicy
import com.kavach.app.core.policy.PolicySnapshot
import com.kavach.app.data.db.AppPolicyEntity
import com.kavach.app.data.db.DomainRuleEntity
import com.kavach.app.data.db.KavachDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Assembles the single source of truth the tunnel reads.
 *
 * Four independent streams - per-app policies, user domain rules, settings and the
 * compiled blocklists - are combined into one immutable [PolicySnapshot]. Anything
 * that changes any of them produces a new snapshot, and the VPN service swaps to it
 * atomically. Nothing in the filtering hot path ever queries the database.
 */
class PolicyRepository(
    private val dao: KavachDao,
    private val settings: SettingsRepository,
    private val blocklists: BlocklistRepository,
    scope: CoroutineScope,
) {

    val snapshot: StateFlow<PolicySnapshot> = combine(
        dao.observePolicies(),
        dao.observeRules(),
        settings.flow,
        blocklists.lists,
    ) { policies, rules, prefs, lists ->
        val defaultZone = prefs.defaultZone

        val policyMap = HashMap<String, AppPolicy>(policies.size * 2)
        for (entity in policies) {
            policyMap[entity.packageName] = entity.toPolicy()
        }

        val perApp = HashMap<String, MutableMap<String, Boolean>>()
        val global = HashMap<String, Boolean>()
        for (rule in rules) {
            val domain = rule.domain.lowercase().trimEnd('.')
            if (domain.isEmpty()) continue
            if (rule.packageName == DomainRuleEntity.GLOBAL) {
                global[domain] = rule.allow
            } else {
                perApp.getOrPut(rule.packageName) { HashMap() }[domain] = rule.allow
            }
        }

        PolicySnapshot(
            policies = policyMap,
            perAppRules = perApp,
            globalRules = global,
            trackers = lists.trackers,
            ads = lists.ads,
            defaultZone = defaultZone,
            paused = prefs.paused,
            loggingEnabled = prefs.loggingEnabled,
            dohEndpointId = prefs.dohEndpointId,
        )
    }.stateIn(scope, SharingStarted.Eagerly, PolicySnapshot.EMPTY)

    // ------------------------------------------------------------------ writes

    fun observePolicies(): Flow<List<AppPolicyEntity>> = dao.observePolicies()

    fun observeRulesFor(packageName: String): Flow<List<DomainRuleEntity>> =
        dao.observeRulesFor(packageName)

    suspend fun policyFor(packageName: String, uid: Int): AppPolicy =
        dao.policyFor(packageName)?.toPolicy()
            ?: AppPolicy.defaultFor(packageName, settings.current().defaultZone)

    /**
     * Moves an app into a zone.
     *
     * Assigning a zone resets the app's network mode and list toggles to that zone's
     * defaults. That is intentional: a zone is a promise about behaviour, and a zone
     * that silently kept contradictory overrides would be a lie.
     */
    suspend fun setZone(packageName: String, uid: Int, zone: Zone) {
        dao.upsertPolicy(
            AppPolicyEntity(
                packageName = packageName,
                uid = uid,
                zoneId = zone.id,
                networkModeId = zone.defaultNetworkMode.id,
                blockTrackers = zone.defaultBlockTrackers,
                blockAds = zone.defaultBlockAds,
                learningMode = existing(packageName)?.learningMode ?: false,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun setNetworkMode(packageName: String, uid: Int, mode: NetworkMode) =
        update(packageName, uid) { it.copy(networkModeId = mode.id) }

    suspend fun setBlockTrackers(packageName: String, uid: Int, value: Boolean) =
        update(packageName, uid) { it.copy(blockTrackers = value) }

    suspend fun setBlockAds(packageName: String, uid: Int, value: Boolean) =
        update(packageName, uid) { it.copy(blockAds = value) }

    suspend fun setLearningMode(packageName: String, uid: Int, value: Boolean) =
        update(packageName, uid) { it.copy(learningMode = value) }

    suspend fun clearPolicy(packageName: String) {
        dao.deletePolicy(packageName)
        dao.clearRulesFor(packageName)
    }

    /** Applies one zone to many apps at once, for the triage flow. */
    suspend fun applyZoneToAll(apps: List<Pair<String, Int>>, zone: Zone) {
        val now = System.currentTimeMillis()
        dao.upsertPolicies(
            apps.map { (packageName, uid) ->
                AppPolicyEntity(
                    packageName = packageName,
                    uid = uid,
                    zoneId = zone.id,
                    networkModeId = zone.defaultNetworkMode.id,
                    blockTrackers = zone.defaultBlockTrackers,
                    blockAds = zone.defaultBlockAds,
                    learningMode = false,
                    updatedAt = now,
                )
            }
        )
    }

    suspend fun setRule(packageName: String, domain: String, allow: Boolean) {
        val normalised = domain.trim().lowercase().trimEnd('.')
        if (normalised.isEmpty()) return
        dao.upsertRule(
            DomainRuleEntity(
                packageName = packageName,
                domain = normalised,
                allow = allow,
                autoLearned = false,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun clearRule(packageName: String, domain: String) =
        dao.deleteRule(packageName, domain.trim().lowercase().trimEnd('.'))

    private suspend fun existing(packageName: String): AppPolicyEntity? = dao.policyFor(packageName)

    private suspend fun update(
        packageName: String,
        uid: Int,
        transform: (AppPolicyEntity) -> AppPolicyEntity,
    ) {
        val zone = settings.current().defaultZone
        val base = existing(packageName) ?: AppPolicyEntity(
            packageName = packageName,
            uid = uid,
            zoneId = zone.id,
            networkModeId = zone.defaultNetworkMode.id,
            blockTrackers = zone.defaultBlockTrackers,
            blockAds = zone.defaultBlockAds,
            learningMode = false,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertPolicy(transform(base).copy(uid = uid, updatedAt = System.currentTimeMillis()))
    }
}

private fun AppPolicyEntity.toPolicy(): AppPolicy = AppPolicy(
    packageName = packageName,
    zone = Zone.fromId(zoneId),
    networkMode = NetworkMode.fromId(networkModeId),
    blockTrackers = blockTrackers,
    blockAds = blockAds,
    learningMode = learningMode,
)
