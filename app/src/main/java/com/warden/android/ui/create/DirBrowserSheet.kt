package com.warden.android.ui.create

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.warden.android.data.model.DirEntry

/**
 * The working-directory picker, backed by `GET /fs/dirs`. Shared by the create
 * flows (new agent and new terminal) so the folder-browsing UX is identical in
 * both. The caller owns the [browser] state and the navigate/choose/dismiss hooks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirBrowserSheet(
    browser: DirBrowser,
    onNavigate: (String?) -> Unit,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val listing = browser.listing
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                text = listing?.path?.ifBlank { "/" } ?: "Choose a folder",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            when {
                browser.error != null -> Text(
                    text = browser.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                browser.loading || listing == null -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                else -> DirEntries(
                    listing.parent,
                    listing.entries,
                    onNavigate,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = onChoose,
                    enabled = listing != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use this folder")
                }
            }
        }
    }
}

@Composable
private fun DirEntries(
    parent: String,
    entries: List<DirEntry>,
    onNavigate: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        if (parent.isNotBlank()) {
            item(key = "..") {
                DirRow(name = "..", onClick = { onNavigate(parent) })
                HorizontalDivider()
            }
        }
        items(entries, key = { it.path }) { entry ->
            DirRow(name = entry.name, onClick = { onNavigate(entry.path) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun DirRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (name == "..") Icons.Filled.KeyboardArrowUp else Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
