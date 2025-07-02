package org.modelix.workspace.manager

import kotlinx.coroutines.CoroutineScope
import org.modelix.workspaces.WorkspaceConfigForBuild
import java.util.UUID

private val LOG = mu.KotlinLogging.logger { }

class WorkspaceBuildManager(
    val coroutinesScope: CoroutineScope,
    val tokenGenerator: (WorkspaceConfigForBuild) -> String,
) {

    private val workspaceImageTasks = ReusableTasks<WorkspaceConfigForBuild, WorkspaceImageTask>()

    fun getOrCreateWorkspaceImageTask(workspaceConfig: WorkspaceConfigForBuild): WorkspaceImageTask {
        return workspaceImageTasks.getOrCreateTask(workspaceConfig) {
            WorkspaceImageTask(workspaceConfig, tokenGenerator, coroutinesScope)
        }
    }

    fun getWorkspaceConfigByTaskId(taskId: UUID): WorkspaceConfigForBuild? {
        return workspaceImageTasks.getAll().find { it.id == taskId }?.workspaceConfig
    }
}

enum class TaskState {
    CREATED,
    ACTIVE,
    CANCELLED,
    COMPLETED,
    UNKNOWN,
}
