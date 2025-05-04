package org.modelix.workspace.manager

import org.modelix.services.workspaces.stubs.models.WorkspaceConfig

class WorkspaceBuildManager {

    fun getImageName(workspace: WorkspaceConfig): String {
        return "modelix-workspaces/ws${workspace.id}"
    }

    fun getImageTag(workspace: WorkspaceConfig): String {
        return "TODO"
    }

}