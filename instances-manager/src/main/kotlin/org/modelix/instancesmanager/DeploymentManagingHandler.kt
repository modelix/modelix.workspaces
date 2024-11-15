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
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class DeploymentManagingHandler : AbstractHandler() {
    override fun handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse) {
        try {
            val redirectedURL = DeploymentManager.INSTANCE.redirect(baseRequest, request) ?: return
            val personalDeploymentName = redirectedURL.instanceName ?: return

            if (DeploymentManager.INSTANCE.isInstanceDisabled(personalDeploymentName)) {
                baseRequest.isHandled = true
                response.contentType = "text/html"
                response.status = HttpServletResponse.SC_OK
                response.writer.append("""<html><body>Instance is disabled. (<a href="/instances-manager/" target="_blank">Manage Instances</a>)</body></html>""")
                return
            }

            DeploymentTimeouts.update(personalDeploymentName)
            val deployment = DeploymentManager.INSTANCE.getDeployment(personalDeploymentName, 3)
                ?: throw RuntimeException("Failed to create deployment " + personalDeploymentName + " for user " + redirectedURL.userToken?.getUserName())
            val readyReplicas = if (deployment.status != null) deployment.status!!.readyReplicas else null
            if (readyReplicas == null || readyReplicas == 0) {
                baseRequest.isHandled = true
                response.contentType = "text/html"
                response.status = HttpServletResponse.SC_OK
                var html = this.javaClass.getResource("/static/status-screen.html")?.readText() ?: ""
                val workspace = DeploymentManager.INSTANCE.getWorkspaceForInstance(personalDeploymentName)

                var progress: Int = 10
                if (DeploymentManager.INSTANCE.getPod(personalDeploymentName)?.status?.phase == "Running") {
                    val log = DeploymentManager.INSTANCE.getPodLogs(personalDeploymentName) ?: ""
                    val string2progress: List<Pair<String, Int>> = listOf(
                        "[init ] container is starting..." to 30,
                        "[supervisor ] starting service 'app'..." to 50,
                        "[app ] + /mps/bin/mps.sh" to 80,
                    )
                    progress = string2progress.lastOrNull { log.contains(it.first) }?.second ?: 20
                }

                if (workspace != null) {
                    html = html.replace("{{workspaceName}}", workspace.name ?: workspace.id)
                } else {
                    progress = 0
                }
                html = html.replace("{{progressPercent}}", progress.toString())
                html = html.replace("{{instanceId}}", personalDeploymentName.name)
                response.writer.append(html)
            }
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }
    }

    companion object {
        private val LOG = mu.KotlinLogging.logger {}
    }
}