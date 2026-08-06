package com.warden.android.data.model

import kotlinx.serialization.Serializable

/** One directory in a [DirListing] (openapi `DirEntry`). */
@Serializable
data class DirEntry(val name: String = "", val path: String = "")

/**
 * `GET /api/v1/fs/dirs?path=…` body (openapi `DirListing`): the immediate
 * subdirectories of [path], used by the spawn sheet's working-dir browser.
 * [parent] is empty at the filesystem root, which lets the browser show/hide
 * an "up" affordance without any local filesystem access.
 */
@Serializable
data class DirListing(
    val path: String = "",
    val parent: String = "",
    val entries: List<DirEntry> = emptyList(),
)
