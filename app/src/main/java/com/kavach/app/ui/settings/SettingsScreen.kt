package com.kavach.app.ui.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kavach.app.BuildConfig
import com.kavach.app.KavachApp
import com.kavach.app.core.blocklist.BlocklistWorker
import com.kavach.app.core.blocklist.Blocklists
import com.kavach.app.core.model.Zone
import com.kavach.app.data.repo.KavachSettings
import com.kavach.app.ui.common.KavachCard
import com.kavach.app.ui.common.LimitationNote
import com.kavach.app.ui.common.SectionHeader
import com.kavach.app.ui.common.SwitchRow
import com.kavach.app.ui.common.formatCount
import com.kavach.app.vpn.DohEndpoint
import com.kavach.app.vpn.DohEndpoints
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsState(
    val settings: KavachSettings = KavachSettings(),
    val lists: Blocklists = Blocklists(),
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KavachApp

    val state: StateFlow<SettingsState> = combine(
        app.settingsRepository.flow,
        app.blocklistRepository.lists,
    ) { settings, lists ->
        SettingsState(settings, lists)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setDoh(id: String) = viewModelScope.launch { app.settingsRepository.setDohEndpoint(id) }
    fun setDefaultZone(zone: Zone) = viewModelScope.launch { app.settingsRepository.setDefaultZone(zone) }
    fun setLogging(value: Boolean) = viewModelScope.launch { app.settingsRepository.setLoggingEnabled(value) }
    fun setAutoStart(value: Boolean) = viewModelScope.launch { app.settingsRepository.setAutoStart(value) }
    fun setPaused(value: Boolean) = viewModelScope.launch { app.settingsRepository.setPaused(value) }
    fun setShowSystemApps(value: Boolean) = viewModelScope.launch { app.settingsRepository.setShowSystemApps(value) }
    fun setAutoUpdate(value: Boolean) = viewModelScope.launch { app.settingsRepository.setBlocklistAutoUpdate(value) }

    /** Bypasses the auto-update preference: the user asked for this one explicitly. */
    fun updateListsNow() {
        WorkManager.getInstance(app).enqueue(
            OneTimeWorkRequestBuilder<BlocklistWorker>()
                .setInputData(workDataOf(BlocklistWorker.KEY_FORCE to true))
                .build()
        )
    }

    fun revertToBundledLists() = viewModelScope.launch {
        app.blocklistRepository.clearDownloads()
    }

    fun clearActivityLog() = viewModelScope.launch { app.database.dao().clearLog() }
}

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        SectionHeader("Shield")
        KavachCard {
            SwitchRow(
                title = "Pause filtering",
                subtitle = "Keeps the tunnel up but allows everything through.",
                checked = settings.paused,
                onCheckedChange = viewModel::setPaused,
            )
            HorizontalDivider()
            SwitchRow(
                title = "Start on boot",
                subtitle = "Bring the shield back automatically after a restart.",
                checked = settings.autoStart,
                onCheckedChange = viewModel::setAutoStart,
            )
        }

        SectionHeader("Encrypted resolver")
        KavachCard {
            DohEndpoints.ALL.forEach { endpoint ->
                EndpointRow(
                    endpoint = endpoint,
                    selected = settings.dohEndpointId == endpoint.id,
                    onSelect = { viewModel.setDoh(endpoint.id) },
                )
            }
            LimitationNote(
                "Allowed questions go out over HTTPS to this resolver, so your network " +
                    "operator cannot read them. If the resolver is unreachable Kavach returns " +
                    "a failure instead of quietly falling back to unencrypted DNS."
            )
        }

        SectionHeader("Default zone for new apps")
        KavachCard {
            Zone.entries.forEach { zone ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setDefaultZone(zone) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.defaultZoneId == zone.id,
                        onClick = { viewModel.setDefaultZone(zone) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(zone.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            zone.tagline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionHeader("Filter lists")
        KavachCard {
            Text(
                "${formatCount(state.lists.trackers.size)} trackers, " +
                    "${formatCount(state.lists.ads.size)} ad domains.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                title = "Update automatically",
                subtitle = "Once a day, on Wi-Fi, when the battery is not low.",
                checked = settings.blocklistAutoUpdate,
                onCheckedChange = viewModel::setAutoUpdate,
            )
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::updateListsNow) { Text("Update now") }
                OutlinedButton(onClick = viewModel::revertToBundledLists) { Text("Revert to bundled") }
            }
        }

        SectionHeader("Activity log")
        KavachCard {
            SwitchRow(
                title = "Record activity",
                subtitle = "Stored only on this device, capped at 20,000 rows, never backed up.",
                checked = settings.loggingEnabled,
                onCheckedChange = viewModel::setLogging,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = viewModel::clearActivityLog) { Text("Delete the log now") }
        }

        SectionHeader("Apps list")
        KavachCard {
            SwitchRow(
                title = "Show system apps",
                subtitle = "Include preinstalled components in the Apps screen.",
                checked = settings.showSystemApps,
                onCheckedChange = viewModel::setShowSystemApps,
            )
        }

        SectionHeader("About")
        KavachCard {
            Text("Kavach ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Kavach never sends your browsing history, app list or activity log anywhere. " +
                    "There is no account, no analytics and no crash reporting. The only network " +
                    "traffic Kavach itself makes is DNS queries to the resolver you picked above " +
                    "and the daily filter-list download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun EndpointRow(endpoint: DohEndpoint, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(endpoint.label, style = MaterialTheme.typography.titleMedium)
            Text(
                endpoint.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
