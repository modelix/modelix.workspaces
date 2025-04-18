package org.modelix.workspace.client

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.modelix.model.lazy.RepositoryId
import org.modelix.mps.sync3.IModelSyncService

private val LOG = KotlinLogging.logger { }

class WorkspaceClientStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        println("### Workspace client loaded for project: ${project.name}")
        DumbService.getInstance(project).smartInvokeLater {
            println("### Index is ready: ${project.name}")
        }

        val syncEnabled: Boolean = getEnvOrLog("WORKSPACE_MODEL_SYNC_ENABLED") == "true"
        if (syncEnabled) {
            val modelUri: String? = getEnvOrLog("MODEL_URI")
            val repoId: String? = getEnvOrLog("REPOSITORY_ID")
            val branchName: String? = getEnvOrLog("REPOSITORY_BRANCH")
            val jwt: String? = getEnvOrLog("INITIAL_JWT_TOKEN")
            if (modelUri != null && repoId != null) {
                val connection = IModelSyncService.getInstance(project).addServer(modelUri)
                if (jwt != null) {
                    connection.setTokenProvider { jwt }
                }
                connection.bind(RepositoryId(repoId).getBranchReference(branchName))
            }
        }
    }
}

private fun getEnvOrLog(name: String): String? {
    val value = System.getenv(name)
    if (value == null) {
        LOG.warn { "Environment variable $name is not set." }
    }
    return value
}
