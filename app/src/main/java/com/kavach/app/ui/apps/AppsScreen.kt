package com.kavach.app.ui.apps

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kavach.app.KavachApp
import com.kavach.app.core.model.AppInfo
import com.kavach.app.core.model.ManagedApp
import com.kavach.app.core.model.NetworkMode
import com.kavach.app.core.model.Zone
import com.kavach.app.ui.common.AppIcon
import com.kavach.app.ui.common.EmptyState
import com.kavach.app.ui.common.color
import com.kavach.app.ui.common.emoji
import com.kavach.app.ui.common.formatCount
import com.kavach.app.ui.theme.BlockRed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppsState(
    val loading: Boolean = true,
    val apps: List<ManagedApp> = emptyList(),
    val query: String = "",
    val zoneFilter: Zone? = null,
)

class AppsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KavachApp
    private val dao = app.database.dao()

    private val installed = MutableStateFlow<List<AppInfo>?>(null)
    private val query = MutableStateFlow("")
    private val zoneFilter = MutableStateFlow<Zone?>(null)

    private val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L

    init {
        viewModelScope.launch {
            app.settingsRepository.flow
                .map { it.showSystemApps }
                .distinctUntilChanged()
                .collect { includeSystem ->
                    installed.value = app.appLister.installedApps(includeSystem)
                }
        }
    }

    private val filters = combine(query, zoneFilter) { q, z -> q to z }

    val state: StateFlow<AppsState> = combine(
        installed,
        dao.observePolicies(),
        dao.observeTrafficCounts(since),
        filters,
        app.settingsRepository.flow,
    ) { apps, policies, counts, (q, zone), prefs ->
        if (apps == null) return@combine AppsState(loading = true, query = q, zoneFilter = zone)

        val policyByPackage = policies.associateBy { it.packageName }
        val countsByPackage = counts.associateBy { it.packageName }
        val needle = q.trim().lowercase()

        val merged = apps.asSequence()
            .map { info ->
                val policy = policyByPackage[info.packageName]
                val effectiveZone = policy?.let { Zone.fromId(it.zoneId) } ?: prefs.defaultZone
                val traffic = countsByPackage[info.packageName]
                ManagedApp(
                    info = info,
                    zone = effectiveZone,
                    networkMode = policy
                        ?.let { NetworkMode.fromId(it.networkModeId) }
                        ?: effectiveZone.defaultNetworkMode,
                    blockTrackers = policy?.blockTrackers ?: effectiveZone.defaultBlockTrackers,
                    blockAds = policy?.blockAds ?: effectiveZone.defaultBlockAds,
                    learningMode = policy?.learningMode ?: false,
                    blockedCount = traffic?.blockedCount ?: 0,
                    allowedCount = traffic?.allowedCount ?: 0,
                )
            }
            .filter { zone == null || it.zone == zone }
            .filter {
                needle.isEmpty() ||
                    it.info.label.lowercase().contains(needle) ||
                    it.info.packageName.lowercase().contains(needle)
            }
            // Noisiest apps first: that is where the user's attention is worth spending.
            .sortedWith(compareByDescending<ManagedApp> { it.blockedCount }
                .thenBy { it.info.label.lowercase() })
            .toList()

        AppsState(loading = false, apps = merged, query = q, zoneFilter = zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setZoneFilter(zone: Zone?) {
        zoneFilter.value = zone
    }
}

@Composable
fun AppsScreen(
    contentPadding: PaddingValues,
    onOpenApp: (String) -> Unit,
    viewModel: AppsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.zoneFilter == null,
                onClick = { viewModel.setZoneFilter(null) },
                label = { Text("All") },
            )
            Zone.entries.forEach { zone ->
                FilterChip(
                    selected = state.zoneFilter == zone,
                    onClick = {
                        viewModel.setZoneFilter(if (state.zoneFilter == zone) null else zone)
                    },
                    label = { Text(zone.label) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            state.loading -> Row(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }

            state.apps.isEmpty() -> EmptyState(
                title = "No apps match",
                body = "Try a different search, or turn on \"Show system apps\" in Settings.",
                contentPadding = PaddingValues(0.dp),
            )

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.apps, key = { it.info.packageName }) { managed ->
                    AppRow(managed = managed, onClick = { onOpenApp(managed.info.packageName) })
                }
            }
        }
    }
}

@Composable
private fun AppRow(managed: ManagedApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(managed.info.packageName)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                managed.info.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(managed.zone.emoji())
                    append("  ")
                    append(managed.zone.label)
                    append("  \u00B7  ")
                    append(managed.networkMode.label)
                    if (managed.learningMode) append("  \u00B7  Learning")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = managed.zone.color(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (managed.blockedCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                formatCount(managed.blockedCount),
                style = MaterialTheme.typography.labelSmall,
                color = BlockRed,
            )
        }
    }
}
