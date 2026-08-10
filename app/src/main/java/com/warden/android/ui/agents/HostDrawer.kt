package com.warden.android.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.warden.android.BuildConfig
import com.warden.android.R
import com.warden.android.data.Connection
import com.warden.android.data.displayHost

private val ActiveDot = Color(0xFF2E7D5B)

/**
 * The side navigation drawer. It is the app's primary navigation now that the bottom
 * bar is gone: a collapsible **Hosts** section (every saved host, the active one
 * flagged live, a per-host disconnect, and an "Add host" action) followed by the
 * top-level **Agents** and **Pipelines** destinations. The app version is pinned to
 * the bottom. Tapping a host switches to it; the disconnect icon forgets it (dropping
 * its saved token) after a confirmation.
 */
@Composable
fun HostDrawer(
    connections: List<Connection>,
    activeLabel: String?,
    onSwitch: (String) -> Unit,
    onAddHost: () -> Unit,
    onDisconnect: (Connection) -> Unit,
    agentsSelected: Boolean,
    pipelinesSelected: Boolean,
    onSelectAgents: () -> Unit,
    onSelectPipelines: () -> Unit,
    // Terminals is a top-level destination only on daemons that model terminals
    // as first-class sessions; hidden otherwise (terminal stays a legacy backend).
    terminalsVisible: Boolean = false,
    terminalsSelected: Boolean = false,
    onSelectTerminals: () -> Unit = {},
) {
    // Hosts start expanded so the active host is visible the first time the drawer opens.
    var hostsExpanded by remember { mutableStateOf(true) }

    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxSize()) {
            // Wordmark: the badge symbol is the "W"; "arden" completes it, tinted with
            // onSurface so it reads as ink on light and paper on dark — matching the
            // light/dark wordmark variants in brand/.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.warden_symbol),
                    contentDescription = "Warden",
                    modifier = Modifier.height(44.dp),
                )
                Text(
                    text = "arden",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Hosts — collapsible section header.
                NavigationDrawerItem(
                    label = { Text("Hosts") },
                    selected = false,
                    icon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                    badge = {
                        Icon(
                            imageVector = if (hostsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (hostsExpanded) "Collapse hosts" else "Expand hosts",
                        )
                    },
                    onClick = { hostsExpanded = !hostsExpanded },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )

                AnimatedVisibility(visible = hostsExpanded) {
                    Column {
                        connections.forEach { host ->
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
                                // Indented under the Hosts header to read as a sublist.
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 12.dp,
                                    top = NavigationDrawerItemDefaults.ItemPadding
                                        .calculateTopPadding(),
                                    bottom = NavigationDrawerItemDefaults.ItemPadding
                                        .calculateBottomPadding(),
                                ),
                            )
                        }

                        NavigationDrawerItem(
                            label = { Text("Add host") },
                            selected = false,
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = onAddHost,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 12.dp,
                                top = NavigationDrawerItemDefaults.ItemPadding
                                    .calculateTopPadding(),
                                bottom = NavigationDrawerItemDefaults.ItemPadding
                                    .calculateBottomPadding(),
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))
                Spacer(Modifier.height(8.dp))

                // Top-level destinations, reusing the bottom bar's old icons.
                NavigationDrawerItem(
                    label = { Text("Agents") },
                    selected = agentsSelected,
                    icon = { Icon(Icons.Filled.SmartToy, contentDescription = null) },
                    onClick = onSelectAgents,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    label = { Text("Pipelines") },
                    selected = pipelinesSelected,
                    icon = { Icon(Icons.Filled.AccountTree, contentDescription = null) },
                    onClick = onSelectPipelines,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                if (terminalsVisible) {
                    NavigationDrawerItem(
                        label = { Text("Terminals") },
                        selected = terminalsSelected,
                        icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        onClick = onSelectTerminals,
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }

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
