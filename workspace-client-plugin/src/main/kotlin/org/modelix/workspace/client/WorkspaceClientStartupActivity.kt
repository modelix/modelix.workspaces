package org.modelix.workspace.client

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class WorkspaceClientStartupActivity : StartupActivity.Background {
    override fun runActivity(project: Project) {
        println("### Workspace client loaded for project: ${project.name}")
        DumbService.getInstance(project).smartInvokeLater {
            println("### Index is ready: ${project.name}")
        }
    }
}
