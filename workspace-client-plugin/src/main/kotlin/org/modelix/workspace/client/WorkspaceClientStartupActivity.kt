package org.modelix.workspace.client

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import org.modelix.model.lazy.RepositoryId
import org.modelix.mps.sync3.IModelSyncService

class WorkspaceClientStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        println("### Workspace client loaded for project: ${project.name}")
        DumbService.getInstance(project).smartInvokeLater {
            println("### Index is ready: ${project.name}")
        }

        val syncEnabled: Boolean = getEnvOrLog("WORKSPACE_MODEL_SYNC_ENABLED") == "true"
        if (syncEnabled) {
            println("model sync is enabled")
            val modelUri: String? = getEnvOrLog("MODEL_URI")
            val repoId: RepositoryId? = getEnvOrLog("REPOSITORY_ID")?.let { RepositoryId(it) }
            val branchName: String? = getEnvOrLog("REPOSITORY_BRANCH")
            val jwt: String? = getEnvOrLog("INITIAL_JWT_TOKEN")
            println("model server: $modelUri")
            println("repository: $repoId")
            println("branch: $branchName")
            println("JWT: $jwt")
            if (modelUri != null && repoId != null) {
                val connection = IModelSyncService.getInstance(project).addServer(modelUri, repositoryId = repoId)
                if (jwt != null) {
                    connection.setTokenProvider { jwt }
                }
                connection.bind(repoId.getBranchReference(branchName))
            }
        }
    }
}

private fun getEnvOrLog(name: String): String? {
    val value = System.getenv(name)
    if (value == null) {
        println("Environment variable $name is not set.")
    }
    return value
}
