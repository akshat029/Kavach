package com.kavach.app.ui.isolation

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kavach.app.KavachApp
import com.kavach.app.isolation.IsolationManager
import com.kavach.app.ui.common.KavachCard
import com.kavach.app.ui.common.LimitationNote
import com.kavach.app.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IsolationState(
    val boundary: IsolationManager.BoundaryState = IsolationManager.BoundaryState(managed = false),
    val canProvision: Boolean = false,
    val message: String? = null,
)

class IsolationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KavachApp
    private val manager = app.isolationManager

    private val internal = MutableStateFlow(IsolationState())
    val state: StateFlow<IsolationState> = internal.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        internal.value = internal.value.copy(
            boundary = manager.boundaryState(),
            canProvision = manager.canProvision(),
        )
    }

    fun provisioningIntent(): Intent = manager.provisioningIntent()

    fun sealBoundary() = act { manager.sealBoundary() }

    fun openBoundary() = act { manager.openBoundary() }

    fun removeProfile() = act { manager.removeProfile() }

    fun dismissMessage() {
        internal.value = internal.value.copy(message = null)
    }

    private fun act(block: () -> Result<Unit>) = viewModelScope.launch {
        val result = block()
        internal.value = internal.value.copy(
            boundary = manager.boundaryState(),
            canProvision = manager.canProvision(),
            // Report failures rather than hiding them: a user who believes the
            // boundary is sealed when it is not is worse off than one who knows.
            message = result.exceptionOrNull()?.message,
        )
    }
}

@Composable
fun IsolationScreen(
    contentPadding: PaddingValues,
    onRequestProvisioning: (Intent) -> Unit,
    viewModel: IsolationViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmRemoval by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        SectionHeader("How isolation works here")
        KavachCard {
            Text(
                "Kavach does not run other apps inside itself.",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Android already gives every app its own Linux user ID and its own data " +
                    "directory, enforced by the kernel and SELinux. An app that loaded other " +
                    "apps into its own process would put them all under one shared user ID and " +
                    "replace that kernel boundary with hooks written in userspace. That is " +
                    "weaker than what your phone already does, not stronger.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "So Kavach uses the strongest boundary Android will hand to a normal app: a " +
                    "managed work profile. That is a second kernel-level user with separate " +
                    "storage, a separate clipboard, separate accounts and no default view into " +
                    "your personal side.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader("Status")
        KavachCard {
            val boundary = state.boundary
            Text(
                when {
                    !boundary.managed -> "No Kavach profile on this device."
                    boundary.fullySealed -> "Profile active and sealed."
                    else -> "Profile active, but not fully sealed."
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (boundary.managed) {
                StatusLine("Sharing into the profile blocked", boundary.sharingBlocked)
                StatusLine("Clipboard crossing blocked", boundary.clipboardBlocked)
            }

            Spacer(Modifier.height(14.dp))

            when {
                !boundary.managed && state.canProvision -> Button(
                    onClick = { onRequestProvisioning(viewModel.provisioningIntent()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create the Kavach profile")
                }

                !boundary.managed -> Text(
                    "This device cannot create a work profile. That usually means one already " +
                        "exists, you are on a secondary user, or the manufacturer disabled the " +
                        "feature. Everything else in Kavach still works.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                boundary.fullySealed -> OutlinedButton(
                    onClick = { viewModel.openBoundary() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow sharing across the boundary")
                }

                else -> Button(
                    onClick = { viewModel.sealBoundary() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Seal the boundary")
                }
            }
        }

        if (state.boundary.managed) {
            SectionHeader("Danger zone")
            KavachCard {
                Text(
                    "Removing the profile permanently deletes every app and file inside it. " +
                        "There is no undo and no backup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { confirmRemoval = true }) {
                    Text("Remove the Kavach profile")
                }
            }
        }

        LimitationNote(
            "A work profile stops apps reading each other's files. It does not stop two apps " +
                "being matched up on the network by the same advertising ID, IP address or " +
                "login. That is what the shield and the zone rules are for, and the two " +
                "together are the point of Kavach."
        )

        Spacer(Modifier.height(40.dp))
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
            },
            title = { Text("That did not work") },
            text = { Text(message) },
        )
    }

    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    viewModel.removeProfile()
                }) {
                    Text("Delete everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") }
            },
            title = { Text("Remove the Kavach profile?") },
            text = {
                Text(
                    "Every app installed inside the profile, and all of their data, will be " +
                        "deleted immediately. This cannot be undone."
                )
            },
        )
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            if (ok) "\u2713" else "\u2717",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.padding(horizontal = 6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
