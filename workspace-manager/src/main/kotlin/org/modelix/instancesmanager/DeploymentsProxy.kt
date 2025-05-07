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

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.DefaultHandler
import org.eclipse.jetty.server.handler.HandlerList
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest
import org.modelix.workspace.manager.WorkspaceInstancesManager
import java.net.URI
import javax.servlet.http.HttpServletRequest

class DeploymentsProxy(val manager: WorkspaceInstancesManager) {
    private val LOG = mu.KotlinLogging.logger {}

    fun startServer() {
        val server = Server(33332)
        val handlerList = HandlerList()
        server.handler = handlerList

        val proxyServlet: ProxyServletWithWebsocketSupport = object : ProxyServletWithWebsocketSupport() {
            override fun dataTransferred(clientSession: Session?, proxySession: Session?) {
//                val deploymentName = InstanceName(proxySession!!.upgradeRequest.host)
//                DeploymentTimeouts.update(deploymentName)
            }

            override fun redirect(request: ServletUpgradeRequest): URI? {
                val redirectedURL = RedirectedURL.redirect(request.httpServletRequest)
                if (redirectedURL == null) return null
                redirectedURL.targetHost = manager.getTargetHost(redirectedURL.instanceId)
                return redirectedURL.getURLToRedirectTo(true)?.let { URI(it) }
            }

            override fun rewriteTarget(clientRequest: HttpServletRequest): String? {
                val redirectedURL = RedirectedURL.redirect(clientRequest)
                if (redirectedURL == null) return null
                redirectedURL.targetHost = manager.getTargetHost(redirectedURL.instanceId)
                return redirectedURL.getURLToRedirectTo(false)
            }
        }

        val proxyHandler = ServletContextHandler()
        proxyHandler.addServlet(ServletHolder(proxyServlet), "/*")
        handlerList.addHandler(proxyHandler)
        handlerList.addHandler(DefaultHandler())
        server.start()
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                try {
                    server.stop()
                } catch (ex: Exception) {
                    println(ex.message)
                    ex.printStackTrace()
                }
            }
        })
    }
}
