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
import org.modelix.authorization.getUserName
import org.modelix.workspaces.WorkspaceBuildStatus
import org.modelix.workspaces.WorkspaceProgressItems
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class DeploymentManagingHandler(val manager: DeploymentManager) : AbstractHandler() {
    override fun handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse) {
        val redirectedURL = manager.redirect(baseRequest, request) ?: return
        val personalDeploymentName = redirectedURL.instanceName ?: return

        // instance disabled on the management page
        if (manager.isInstanceDisabled(personalDeploymentName)) {
            baseRequest.isHandled = true
            response.contentType = "text/html"
            response.status = HttpServletResponse.SC_OK
            response.writer.append("""<html><body>Instance is disabled. (<a href="/workspace-manager/instances/" target="_blank">Manage Instances</a>)</body></html>""")
            return
        }

        val workspace = manager.getWorkspaceForPath(redirectedURL.workspaceReference) ?: return
        var progress: Pair<Int, String> = 0 to "Waiting for start of workspace build job"
        var statusLink = "/workspace-manager/instances/log/${personalDeploymentName.name}/"
        var readyForForwarding = false

        val progressItems = WorkspaceProgressItems()
        val status = manager.getWorkspaceStatus(workspace.hash())

        fun loadBuildStatus() {
            progress = when (status) {
                WorkspaceBuildStatus.New -> {
                    progressItems.build.enqueue.started = true
                    10 to "Waiting for start of workspace build job"
                }
                WorkspaceBuildStatus.Queued -> {
                    progressItems.build.enqueue.done = true
                    20 to "Workspace is queued for building"
                }
                WorkspaceBuildStatus.Running -> {
                    progressItems.build.startKubernetesJob.started = true
                    30 to "Workspace build is running"
                }
                WorkspaceBuildStatus.FailedBuild, WorkspaceBuildStatus.FailedZip -> {
                    0 to "Workspace build failed"
                }
                WorkspaceBuildStatus.AllSuccessful -> 40 to "Workspace build is done"
                WorkspaceBuildStatus.ZipSuccessful -> 40 to "Workspace build is done"
            }
            progressItems.build.enqueue.done = status != WorkspaceBuildStatus.New
            progressItems.parseLog(manager.getWorkspaceBuildLog(workspace.hash()))
        }

        if (status.canStartInstance()) {
            DeploymentTimeouts.update(personalDeploymentName)
            val deployment = manager.getDeployment(personalDeploymentName, 10)
                ?: throw RuntimeException("Failed creating deployment " + personalDeploymentName + " for user " + redirectedURL.userToken?.getUserName())
            progressItems.container.createDeployment.done = true
            val readyReplicas = deployment.status?.readyReplicas ?: 0
            val waitForIndexer = request.getParameter("waitForIndexer") != "false"
            val waitingForIndexer = waitForIndexer && !manager.isIndexerReady(personalDeploymentName)
            readyForForwarding = readyReplicas > 0
            if (readyForForwarding && !waitingForIndexer) {
                progress = 100 to "Workspace instance is ready"
            } else {
                loadBuildStatus()
                progress = 50 to "Workspace deployment created. Waiting for startup of the container."
                if (manager.getPod(personalDeploymentName)?.status?.phase == "Running") {
                    progressItems.container.startContainer.started = true
                    progress = 50 to "Workspace container is running"
                    val log = manager.getPodLogs(personalDeploymentName) ?: ""
                    val string2progress: List<Pair<String, Pair<Int, String>>> = listOf(
                        "] container is starting..." to (60 to "Workspace container is running"),
                        "] starting service 'app'..." to (70 to "Preparing MPS project"),
                        "] + /mps/bin/mps.sh" to (80 to "MPS is starting"),
                        "### Workspace client loaded" to (90 to "Project is loaded. Waiting for indexer."),
                        "### Index is ready" to (100 to "Indexing is done. Project is ready."),
                    )
                    string2progress.lastOrNull { log.contains(it.first) }?.second?.let {
                        progress = it
                    }

                    if (log.contains("] container is starting...")) {
                        progressItems.container.startContainer.done = true
                    }
                    if (log.contains("] starting service 'app'...")) {
                        progressItems.container.prepareMPS.started = true
                    }
                    if (log.contains("] + /mps/bin/mps.sh")) {
                        progressItems.container.prepareMPS.done = true
                        progressItems.container.startMPS.started = true
                    }
                    if (log.contains("### Workspace client loaded")) {
                        progressItems.container.startMPS.done = true
                        progressItems.container.runIndexer.started = true
                    }
                    if (log.contains("### Index is ready")) {
                        progressItems.container.runIndexer.done = true
                    }
                }
            }
        } else {
            statusLink = "/workspace-manager/${workspace.hash()}/buildlog"
            loadBuildStatus()
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

            val progressItemsAsHtml = progressItems.getItems().entries.joinToString("<tr><td>&nbsp;</td><td>&nbsp;</td></tr>") { group ->
                group.value.joinToString("") {
                    "<tr><td>${it.description}</td><td>${it.statusText()}</td></tr>"
                }
            }
            html = html.replace("{{progressItems}}", progressItemsAsHtml)
            html = html.replace("{{skipIndexerLinkVisibility}}", if (readyForForwarding) "visible" else "hidden")

            response.writer.append(html)
        }
    }

    companion object {
        private val LOG = mu.KotlinLogging.logger {}
    }
}
