package com.warden.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.warden.android.data.Connection
import com.warden.android.data.displayHost

/**
 * A TopAppBar title that doubles as a host picker: it shows the screen [title] over
 * the active host, and — when more than one host is saved — a dropdown to switch
 * between them. Shared by the Agents and Pipelines tabs so both bars read the same;
 * the hamburger/drawer remains the full manager (add/disconnect), this is the quick swap.
 */
@Composable
fun HostPickerTitle(
    title: String,
    hostLabel: String,
    hosts: List<Connection>,
    activeLabel: String?,
    onSwitchHost: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val multi = hosts.size > 1
    val shownHost = remember(hostLabel) { hostLabel.substringAfter("://").trimEnd('/') }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(enabled = multi) { expanded = true },
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(title)
                if (shownHost.isNotBlank()) {
                    Text(
                        text = shownHost,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (multi) {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Switch host",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            hosts.forEach { host ->
                DropdownMenuItem(
                    text = { Text(host.displayHost(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (host.label == activeLabel) {
                            Icon(Icons.Filled.Check, contentDescription = "Active host")
                        }
                    },
                    onClick = {
                        expanded = false
                        onSwitchHost(host.label)
                    },
                )
            }
        }
    }
}
