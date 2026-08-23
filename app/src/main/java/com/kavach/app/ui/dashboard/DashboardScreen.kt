package com.kavach.app.ui.dashboard

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kavach.app.KavachApp
import com.kavach.app.R
import com.kavach.app.core.blocklist.Blocklists
import com.kavach.app.data.db.DomainCount
import com.kavach.app.ui.common.KavachCard
import com.kavach.app.ui.common.LimitationNote
import com.kavach.app.ui.common.SectionHeader
import com.kavach.app.ui.common.formatCount
import com.kavach.app.ui.theme.BlockRed
import com.kavach.app.ui.theme.ShieldGreen
import com.kavach.app.vpn.KavachVpnService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val blocked: Int = 0,
    val total: Int = 0,
    val topBlocked: List<DomainCount> = emptyList(),
    val lists: Blocklists = Blocklists(),
) {
    val allowed: Int get() = (total - blocked).coerceAtLeast(0)
    val blockRate: Float get() = if (total == 0) 0f else blocked.toFloat() / total
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KavachApp
    private val dao = app.database.dao()

    /** Fixed at screen creation, so the headline always means the same window. */
    private val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L

    private val counts = combine(
        dao.observeBlockedCount(since),
        dao.observeTotalCount(since),
        dao.observeTopBlocked(since, TOP_LIMIT),
    ) { blocked, total, top -> Triple(blocked, total, top) }

    val state: StateFlow<DashboardState> = combine(
        KavachVpnService.isRunning,
        counts,
        app.blocklistRepository.lists,
        app.settingsRepository.flow,
    ) { running, (blocked, total, top), lists, prefs ->
        DashboardState(
            running = running,
            paused = prefs.paused,
            blocked = blocked,
            total = total,
            topBlocked = top,
            lists = lists,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun setPaused(paused: Boolean) = viewModelScope.launch {
        app.settingsRepository.setPaused(paused)
    }

    private companion object {
        const val TOP_LIMIT = 6
    }
}

@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    onStartShield: () -> Unit,
    onStopShield: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenLog: () -> Unit,
    viewModel: DashboardViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        ShieldHeader(
            running = state.running,
            paused = state.paused,
            onStart = onStartShield,
            onStop = onStopShield,
        )

        SectionHeader("Last 24 hours")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                modifier = Modifier.weight(1f),
                value = formatCount(state.blocked),
                label = "Refused",
                accent = BlockRed,
            )
            StatCard(
                modifier = Modifier.weight(1f),
                value = formatCount(state.allowed),
                label = "Allowed",
                accent = ShieldGreen,
            )
            StatCard(
                modifier = Modifier.weight(1f),
                value = "${(state.blockRate * 100).toInt()}%",
                label = "Blocked",
                accent = MaterialTheme.colorScheme.primary,
            )
        }

        if (state.topBlocked.isNotEmpty()) {
            SectionHeader("Most refused domains")
            KavachCard {
                state.topBlocked.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.domain,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatCount(entry.hits),
                            style = MaterialTheme.typography.labelSmall,
                            color = BlockRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (index != state.topBlocked.lastIndex) Spacer(Modifier.height(2.dp))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenLog) { Text("See full activity") }
            }
        }

        SectionHeader("Filter lists")
        KavachCard {
            Text(
                "${formatCount(state.lists.trackers.size)} tracker domains and " +
                    "${formatCount(state.lists.ads.size)} ad domains loaded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when (state.lists.source) {
                    Blocklists.Source.DOWNLOADED -> "Updated from the Kavach list feed."
                    Blocklists.Source.BUNDLED -> "Using the lists bundled with this build."
                    Blocklists.Source.NONE -> "Lists are still loading."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenApps) { Text("Assign apps to zones") }
        }

        LimitationNote(
            "Kavach filters at the DNS layer. An app that connects straight to a " +
                "hard-coded IP address never asks a question, so it cannot be refused " +
                "this way. See ARCHITECTURE.md for exactly what this does and does not stop."
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ShieldHeader(
    running: Boolean,
    paused: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val active = running && !paused
    val accent = if (active) ShieldGreen else MaterialTheme.colorScheme.outline

    KavachCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        paused && running -> "Paused"
                        running -> stringResource(R.string.tunnel_active)
                        else -> stringResource(R.string.tunnel_inactive)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = when {
                        paused && running -> "Everything is being allowed through."
                        running -> "Every DNS question on this device is being checked."
                        else -> "Nothing is being filtered right now."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (running) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("Turn off shield")
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ShieldGreen),
            ) {
                Text("Turn on shield", color = Color.White)
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    value: String,
    label: String,
    accent: Color,
) {
    KavachCard(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
