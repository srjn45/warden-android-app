package com.warden.android.ui.agents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.warden.android.BuildConfig
import com.warden.android.data.Connection
import com.warden.android.data.displayHost

private val ActiveDot = Color(0xFF2E7D5B)

/**
 * The side navigation drawer: every saved host, the active one flagged live, a
 * per-host disconnect, an "Add host" action, and the app version pinned to the
 * bottom. Tapping a host switches to it; the disconnect icon forgets it (dropping
 * its saved token) after a confirmation.
 */
@Composable
fun HostDrawer(
    connections: List<Connection>,
    activeLabel: String?,
    onSwitch: (String) -> Unit,
    onAddHost: () -> Unit,
    onDisconnect: (Connection) -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Hosts",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 12.dp),
            )
            HorizontalDivider()
            // Breathing room so the first host's selected highlight doesn't butt up
            // against the divider under the "Hosts" heading.
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(connections, key = { it.label }) { host ->
                    val active = host.label == activeLabel
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = host.displayHost(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        selected = active,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Circle,
                                contentDescription = if (active) "Active" else null,
                                tint = if (active) ActiveDot else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.height(12.dp),
                            )
                        },
                        badge = {
                            IconButton(onClick = { onDisconnect(host) }) {
                                Icon(
                                    imageVector = Icons.Filled.LinkOff,
                                    contentDescription = "Disconnect ${host.displayHost()}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { onSwitch(host.label) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }

            HorizontalDivider()
            NavigationDrawerItem(
                label = { Text("Add host") },
                selected = false,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = onAddHost,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )

            HorizontalDivider()
            Text(
                text = "Warden v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
fun DisconnectHostDialog(
    connection: Connection,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect ${connection.displayHost()}?") },
        text = {
            Text(
                "This forgets the host and its saved token on this device. Live agents " +
                    "on the daemon are untouched — you can reconnect anytime with the token.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
