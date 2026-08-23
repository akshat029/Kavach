package com.kavach.app.ui.apps

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kavach.app.KavachApp
import com.kavach.app.core.model.NetworkMode
import com.kavach.app.core.model.Zone
import com.kavach.app.data.db.DomainCount
import com.kavach.app.data.db.DomainRuleEntity
import com.kavach.app.ui.common.AppIcon
import com.kavach.app.ui.common.KavachCard
import com.kavach.app.ui.common.LimitationNote
import com.kavach.app.ui.common.SectionHeader
import com.kavach.app.ui.common.SwitchRow
import com.kavach.app.ui.common.formatCount
import com.kavach.app.ui.nav.Destination
import com.kavach.app.ui.theme.BlockRed
import com.kavach.app.ui.theme.ShieldGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppDetailState(
    val packageName: String = "",
    val label: String = "",
    val uid: Int = -1,
    val zone: Zone = Zone.DEFAULT,
    val networkMode: NetworkMode = Zone.DEFAULT.defaultNetworkMode,
    val blockTrackers: Boolean = true,
    val blockAds: Boolean = true,
    val learningMode: Boolean = false,
    val rules: List<DomainRuleEntity> = emptyList(),
    val seenDomains: List<DomainCount> = emptyList(),
    val installed: Boolean = true,
)

class AppDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val app = application as KavachApp
    private val dao = app.database.dao()

    private val packageName: String =
        savedStateHandle.get<String>(Destination.AppDetail.ARG).orEmpty()

    private val seen = MutableStateFlow<List<DomainCount>>(emptyList())

    private val identity: Triple<String, Int, Boolean> = resolveIdentity()

    init {
        refreshSeenDomains()
    }

    val state: StateFlow<AppDetailState> = combine(
        dao.observePolicies(),
        dao.observeRulesFor(packageName),
        seen,
        app.settingsRepository.flow,
    ) { policies, rules, seenDomains, prefs ->
        val policy = policies.firstOrNull { it.packageName == packageName }
        val zone = policy?.let { Zone.fromId(it.zoneId) } ?: prefs.defaultZone
        AppDetailState(
            packageName = packageName,
            label = identity.first,
            uid = identity.second,
            zone = zone,
            networkMode = policy
                ?.let { NetworkMode.fromId(it.networkModeId) }
                ?: zone.defaultNetworkMode,
            blockTrackers = policy?.blockTrackers ?: zone.defaultBlockTrackers,
            blockAds = policy?.blockAds ?: zone.defaultBlockAds,
            learningMode = policy?.learningMode ?: false,
            rules = rules,
            seenDomains = seenDomains,
            installed = identity.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppDetailState())

    private fun resolveIdentity(): Triple<String, Int, Boolean> = try {
        val pm = app.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        Triple(pm.getApplicationLabel(info).toString(), info.uid, true)
    } catch (_: PackageManager.NameNotFoundException) {
        // The app was uninstalled while its policy row survives. Show it anyway so
        // the user can clean up the leftover rules.
        Triple(packageName, -1, false)
    } catch (_: Throwable) {
        Triple(packageName, -1, false)
    }

    private fun refreshSeenDomains() = viewModelScope.launch {
        seen.value = runCatching { dao.domainsSeenFor(packageName) }
            .getOrDefault(emptyList())
            .take(SEEN_LIMIT)
    }

    fun setZone(zone: Zone) = viewModelScope.launch {
        app.policyRepository.setZone(packageName, identity.second, zone)
    }

    fun setNetworkMode(mode: NetworkMode) = viewModelScope.launch {
        app.policyRepository.setNetworkMode(packageName, identity.second, mode)
    }

    fun setBlockTrackers(value: Boolean) = viewModelScope.launch {
        app.policyRepository.setBlockTrackers(packageName, identity.second, value)
    }

    fun setBlockAds(value: Boolean) = viewModelScope.launch {
        app.policyRepository.setBlockAds(packageName, identity.second, value)
    }

    fun setLearningMode(value: Boolean) = viewModelScope.launch {
        app.policyRepository.setLearningMode(packageName, identity.second, value)
    }

    fun addRule(domain: String, allow: Boolean) = viewModelScope.launch {
        app.policyRepository.setRule(packageName, domain, allow)
        refreshSeenDomains()
    }

    fun removeRule(domain: String) = viewModelScope.launch {
        app.policyRepository.clearRule(packageName, domain)
    }

    fun resetToDefault() = viewModelScope.launch {
        app.policyRepository.clearPolicy(packageName)
    }

    private companion object {
        const val SEEN_LIMIT = 40
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    onBack: () -> Unit,
    onOpenSystemSettings: (String) -> Unit,
    viewModel: AppDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draftDomain by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.label.ifEmpty { state.packageName },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                AppIcon(state.packageName, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.packageName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.installed) "uid ${state.uid}" else "No longer installed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader("Zone")
            KavachCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Zone.entries.forEach { zone ->
                        FilterChip(
                            selected = state.zone == zone,
                            onClick = { viewModel.setZone(zone) },
                            label = { Text(zone.label) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    state.zone.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Network")
            KavachCard {
                NetworkMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = state.networkMode == mode,
                            onClick = { viewModel.setNetworkMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }

            SectionHeader("Filters")
            KavachCard {
                SwitchRow(
                    title = "Block known trackers",
                    subtitle = "Analytics and profiling endpoints.",
                    checked = state.blockTrackers,
                    enabled = state.networkMode == NetworkMode.FILTERED,
                    onCheckedChange = viewModel::setBlockTrackers,
                )
                HorizontalDivider()
                SwitchRow(
                    title = "Block ad networks",
                    subtitle = "Advertising exchanges and creative servers.",
                    checked = state.blockAds,
                    enabled = state.networkMode == NetworkMode.FILTERED,
                    onCheckedChange = viewModel::setBlockAds,
                )
                HorizontalDivider()
                SwitchRow(
                    title = "Learning mode",
                    subtitle = "Record what would be blocked without actually blocking it.",
                    checked = state.learningMode,
                    onCheckedChange = viewModel::setLearningMode,
                )
            }

            SectionHeader("Your rules")
            KavachCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draftDomain,
                        onValueChange = { draftDomain = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("example.com") },
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.addRule(draftDomain, allow = true)
                            draftDomain = ""
                        },
                        enabled = draftDomain.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Always allow")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.addRule(draftDomain, allow = false)
                            draftDomain = ""
                        },
                        enabled = draftDomain.isNotBlank(),
                    ) {
                        Text("Always block")
                    }
                }

                if (state.rules.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    state.rules.forEach { rule ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (rule.allow) "ALLOW" else "BLOCK",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (rule.allow) ShieldGreen else BlockRed,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                rule.domain,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { viewModel.removeRule(rule.domain) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove rule")
                            }
                        }
                    }
                }

                if (state.networkMode == NetworkMode.ALLOWLIST_ONLY && state.rules.none { it.allow }) {
                    LimitationNote(
                        "This app is set to allowlist only and has no allowed domains yet, " +
                            "so every lookup will be refused. Turn on Learning mode for a day, " +
                            "then allow the domains it actually needs from the list below."
                    )
                }
            }

            if (state.seenDomains.isNotEmpty()) {
                SectionHeader("Domains this app has asked for")
                KavachCard {
                    state.seenDomains.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.domain,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${formatCount(entry.hits)} lookups",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.addRule(entry.domain, true) }) {
                                Text("Allow")
                            }
                            TextButton(onClick = { viewModel.addRule(entry.domain, false) }) {
                                Text("Block")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.resetToDefault() }) {
                    Text("Reset to default")
                }
                OutlinedButton(onClick = { onOpenSystemSettings(state.packageName) }) {
                    Text("System settings")
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
