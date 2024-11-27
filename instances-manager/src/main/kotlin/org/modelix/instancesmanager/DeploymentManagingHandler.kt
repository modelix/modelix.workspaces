/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelix.instancesmanager

import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.modelix.workspaces.WorkspaceBuildStatus
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class DeploymentManagingHandler(val manager: DeploymentManager) : AbstractHandler() {
    override fun handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse) {
        val redirectedURL = DeploymentManager.INSTANCE.redirect(baseRequest, request) ?: return
        val personalDeploymentName = redirectedURL.instanceName ?: return

        // instance disabled on the management page
        if (DeploymentManager.INSTANCE.isInstanceDisabled(personalDeploymentName)) {
            baseRequest.isHandled = true
            response.contentType = "text/html"
            response.status = HttpServletResponse.SC_OK
            response.writer.append("""<html><body>Instance is disabled. (<a href="/instances-manager/" target="_blank">Manage Instances</a>)</body></html>""")
            return
        }

        val workspace = manager.getWorkspaceForPath(redirectedURL.workspaceReference) ?: return
        var progress: Pair<Int, String> = 0 to "Waiting for start of workspace build job"
        var statusLink = "/instances-manager/log/${personalDeploymentName.name}/"

        val status = manager.getWorkspaceStatus(workspace.hash())
        if (status.canStartInstance()) {
            DeploymentTimeouts.update(personalDeploymentName)
            val deployment = DeploymentManager.INSTANCE.getDeployment(personalDeploymentName, 10)
                ?: throw RuntimeException("Failed creating deployment " + personalDeploymentName + " for user " + redirectedURL.userToken?.getUserName())
            val readyReplicas = deployment.status?.readyReplicas ?: 0
            val waitingForIndexer = workspace.workspace.waitForIndexer && !manager.isIndexerReady(personalDeploymentName)
            if (readyReplicas > 0 && waitingForIndexer) {
                progress = 100 to "Workspace instance is ready"
            } else {
                progress = 50 to "Workspace deployment created. Waiting for startup of the container."
                if (DeploymentManager.INSTANCE.getPod(personalDeploymentName)?.status?.phase == "Running") {
                    progress = 50 to "Workspace container is running"
                    val log = DeploymentManager.INSTANCE.getPodLogs(personalDeploymentName) ?: ""
                    val string2progress: List<Pair<String, Pair<Int, String>>> = listOf(
                        "[init ] container is starting..." to (60 to "Workspace container is running"),
                        "[supervisor ] starting service 'app'..." to (70 to "Preparing MPS project"),
                        "[app ] + /mps/bin/mps.sh" to (80 to "MPS is starting"),
                        "### Workspace client loaded" to (90 to "Project is loaded. Waiting for indexer."),
                        "### Index is ready" to (100 to "Indexing is done. Project is ready."),
                    )
                    string2progress.lastOrNull { log.contains(it.first) }?.second?.let {
                        progress = it
                    }
                }
            }
        } else {
            // workspace not built yet
            statusLink = "/workspace-manager/${workspace.hash()}/buildlog"
            progress = when (status) {
                WorkspaceBuildStatus.New -> 10 to "Waiting for start of workspace build job"
                WorkspaceBuildStatus.Queued -> 20 to "Workspace is queued for building"
                WorkspaceBuildStatus.Running -> 30 to "Workspace build is running"
                WorkspaceBuildStatus.FailedBuild, WorkspaceBuildStatus.FailedZip -> {
                    0 to "Workspace build failed"
                }
                WorkspaceBuildStatus.AllSuccessful -> 40 to "Workspace build is done"
                WorkspaceBuildStatus.ZipSuccessful -> 40 to "Workspace build is done"
            }
        }


        if (progress.first < 100) {
            baseRequest.isHandled = true
            response.contentType = "text/html"
            response.status = HttpServletResponse.SC_OK
            var html = this.javaClass.getResource("/static/status-screen.html")?.readText() ?: ""
            html = html.replace("{{workspaceName}}", workspace.name ?: workspace.id)
            html = html.replace("{{progressPercent}}", progress.first.toString())
            html = html.replace("{{instanceId}}", personalDeploymentName.name)
            html = html.replace("{{statusSummary}}", progress.second)
            html = html.replace("{{statusLink}}", statusLink)
            response.writer.append(html)
        }
    }

    companion object {
        private val LOG = mu.KotlinLogging.logger {}
    }
}