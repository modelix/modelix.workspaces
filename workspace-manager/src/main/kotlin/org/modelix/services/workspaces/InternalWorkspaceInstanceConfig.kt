package org.modelix.services.workspaces

import org.modelix.services.workspaces.stubs.models.WorkspaceInstance
import org.modelix.workspaces.InternalWorkspaceConfig

data class InternalWorkspaceInstanceConfig(
    val instanceConfig: WorkspaceInstance,
    val workspaceConfig: InternalWorkspaceConfig,
) {
    val instanceId: String get() = instanceConfig.id
    val workspaceId: String get() = workspaceConfig.id
}
