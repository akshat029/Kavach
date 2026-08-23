package com.kavach.app.ui.log

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kavach.app.core.model.Verdict
import com.kavach.app.data.db.ConnectionLogEntity
import com.kavach.app.ui.common.AppIcon
import com.kavach.app.ui.common.EmptyState
import com.kavach.app.ui.common.formatTime
import com.kavach.app.ui.theme.BlockRed
import com.kavach.app.ui.theme.ShieldGreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LogState(
    val entries: List<ConnectionLogEntity> = emptyList(),
    val onlyBlocked: Boolean = false,
    val query: String = "",
    val loggingEnabled: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KavachApp
    private val dao = app.database.dao()

    private val onlyBlocked = MutableStateFlow(false)
    private val query = MutableStateFlow("")

    private val entries = combine(onlyBlocked, query.debounce(200)) { blocked, q -> blocked to q }
        .flatMapLatest { (blocked, q) ->
            dao.observeLog(
                onlyBlocked = if (blocked) 1 else 0,
                packageName = null,
                query = q.trim(),
                limit = PAGE_LIMIT,
            )
        }

    val state: StateFlow<LogState> = combine(
        entries,
        onlyBlocked,
        query,
        app.settingsRepository.flow,
    ) { rows, blocked, q, prefs ->
        LogState(
            entries = rows,
            onlyBlocked = blocked,
            query = q,
            loggingEnabled = prefs.loggingEnabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogState())

    fun setOnlyBlocked(value: Boolean) {
        onlyBlocked.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun clear() = viewModelScope.launch { dao.clearLog() }

    private companion object {
        const val PAGE_LIMIT = 500
    }
}

@Composable
fun LogScreen(
    contentPadding: PaddingValues,
    onOpenApp: (String) -> Unit,
    viewModel: LogViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search domains") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.onlyBlocked,
                onClick = { viewModel.setOnlyBlocked(!state.onlyBlocked) },
                label = { Text("Refused only") },
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.clear() }) { Text("Clear") }
        }

        Spacer(Modifier.height(4.dp))

        when {
            !state.loggingEnabled && state.entries.isEmpty() -> EmptyState(
                title = "Activity logging is off",
                body = "Turn it back on in Settings to see what each app is asking for.",
                contentPadding = PaddingValues(0.dp),
            )

            state.entries.isEmpty() -> EmptyState(
                title = "Nothing recorded yet",
                body = "Turn on the shield and use your phone for a few minutes. " +
                    "Every DNS question will appear here.",
                contentPadding = PaddingValues(0.dp),
            )

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.entries, key = { it.id }) { entry ->
                    LogRow(entry = entry, onClick = { onOpenApp(entry.packageName) })
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: ConnectionLogEntity, onClick: () -> Unit) {
    val accent = if (entry.blocked) BlockRed else ShieldGreen

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(entry.packageName, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.domain.ifEmpty { "(root)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(entry.queryType)
                    append("  \u00B7  ")
                    append(reasonLabel(entry.reasonId))
                    entry.detail?.takeIf { it.isNotBlank() }?.let {
                        append("  \u00B7  ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatTime(entry.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Reason ids are persisted enum names; an unknown one must not crash the screen. */
private fun reasonLabel(reasonId: String): String =
    runCatching { Verdict.Reason.valueOf(reasonId).label }.getOrDefault(reasonId)
