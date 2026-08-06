package com.warden.android.data.model

import kotlinx.serialization.Serializable

/** A built-in agent role for the spawn-sheet picker (openapi `RoleInfo`). */
@Serializable
data class RoleInfo(val name: String = "", val description: String = "")

/** `GET /api/v1/roles` body: warden's fixed roles (general first). */
@Serializable
data class RolesResponse(val roles: List<RoleInfo> = emptyList())
