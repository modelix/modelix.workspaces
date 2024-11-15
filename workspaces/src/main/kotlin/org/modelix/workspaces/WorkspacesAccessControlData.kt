package org.modelix.workspaces

import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.serialization.Serializable
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.authorization.permissions.PermissionEvaluator

@Serializable
data class WorkspacesAccessControlData(
    /**
     * User ID to granted permission IDs
     */
    val grantsToUsers: Map<String, Set<String>> = emptyMap(),

    /**
     * Grants based on user roles extracted from the JWT token.
     */
    val grantsToRoles: Map<String, Set<String>> = emptyMap()
) {
    fun load(jwt: DecodedJWT, permissionEvaluator: PermissionEvaluator) {
        val util = ModelixJWTUtil()
        val userId = util.extractUserId(jwt)
        for (permissionId in (grantsToUsers[userId] ?: emptyList())) {
            permissionEvaluator.grantPermission(permissionId)
        }
        val roles = util.extractUserRoles(jwt)
        for (role in roles) {
            for (permissionId in (grantsToRoles[role] ?: emptyList())) {
                permissionEvaluator.grantPermission(permissionId)
            }
        }
    }

    fun withGrantToRole(role: String, permissionId: String): WorkspacesAccessControlData {
        return copy(grantsToRoles = grantsToRoles + (role to (grantsToRoles[role] ?: emptySet()) + permissionId))
    }

    fun withGrantToUser(user: String, permissionId: String): WorkspacesAccessControlData {
        return copy(grantsToUsers = grantsToUsers + (user to (grantsToUsers[user] ?: emptySet()) + permissionId))
    }
}
