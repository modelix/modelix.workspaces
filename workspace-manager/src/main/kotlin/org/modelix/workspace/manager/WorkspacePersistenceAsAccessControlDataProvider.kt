package org.modelix.workspace.manager

import org.modelix.authorization.IAccessControlDataProvider
import org.modelix.authorization.permissions.PermissionParts
import org.modelix.workspaces.WorkspacePersistence

class WorkspacePersistenceAsAccessControlDataProvider(val workspacePersistence: WorkspacePersistence) : IAccessControlDataProvider {
    override fun getGrantedPermissionsForRole(role: String): Set<PermissionParts> {
        return workspacePersistence.getAccessControlData().grantsToRoles[role].orEmpty()
            .map { PermissionParts.fromString(it) }.toSet()
    }

    override fun getGrantedPermissionsForUser(userId: String): Set<PermissionParts> {
        return workspacePersistence.getAccessControlData().grantsToUsers[userId].orEmpty()
            .map { PermissionParts.fromString(it) }.toSet()
    }
}