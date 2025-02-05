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

import com.auth0.jwt.JWT
import org.eclipse.jetty.server.Request
import org.modelix.authorization.AccessTokenPrincipal
import org.modelix.authorization.nullIfInvalid
import javax.servlet.http.HttpServletRequest

class RedirectedURL(
    val remainingPath: String,
    val workspaceReference: String,
    val sharedInstanceName: String,
    var instanceName: InstanceName?,
    val userToken: AccessTokenPrincipal?,
) {

    fun getURLToRedirectTo(websocket: Boolean): String? {
        var url = (if (websocket) "ws" else "http") + "://"
        url += if (instanceName != null) instanceName?.name else workspaceReference
        url += if (remainingPath.startsWith("/ide")) {
            ":5800" + remainingPath.substring("/ide".length)
        } else if (remainingPath.startsWith("/generator")) {
            // see https://github.com/modelix/modelix.mps-plugins/blob/bb70966087e2f41c263a7fe4d292e4722d50b9d1/mps-generator-execution-plugin/src/main/kotlin/org/modelix/mps/generator/web/GeneratorExecutionServer.kt#L78
            ":33335" + remainingPath.substring("/generator".length)
        } else if (remainingPath.startsWith("/diff")) {
            // see https://github.com/modelix/modelix.mps-plugins/blob/bb70966087e2f41c263a7fe4d292e4722d50b9d1/mps-diff-plugin/src/main/kotlin/org/modelix/ui/diff/DiffServer.kt#L82
            ":33334" + remainingPath.substring("/diff".length)
        } else if (remainingPath.startsWith("/port/")) {
            val matchResults = PORT_MATCHER.matchEntire(remainingPath) ?: return null
            val portString = matchResults.groupValues[1]
            val portNumber = portString.toInt()
            if (portNumber > HIGHEST_VALID_PORT_NUMBER) {
                return null
            }
            val pathAfterPort = matchResults.groupValues[2]
            ":$portString$pathAfterPort"
        } else {
            ":33333$remainingPath"
        }
        return url
    }

    companion object {
        private const val HIGHEST_VALID_PORT_NUMBER = 65535
        private val PORT_MATCHER = Regex("/port/(\\d{1,5})(/.*)?")
        fun redirect(baseRequest: Request?, request: HttpServletRequest): RedirectedURL? {
            var remainingPath = request.requestURI
            if (!remainingPath.startsWith("/")) return null
            remainingPath = remainingPath.substring(1)
            val workspaceReference = remainingPath.substringBefore('/')
            remainingPath = remainingPath.substringAfter('/')
            val sharedInstanceName = remainingPath.substringBefore('/')
            remainingPath = remainingPath.substringAfter('/')
            if (request.queryString != null) remainingPath += "?" + request.queryString

            val userId = getUserIdFromAuthHeader(request)
            return RedirectedURL("/" + remainingPath, workspaceReference, sharedInstanceName, null, userId)
        }

        fun getUserIdFromAuthHeader(request: HttpServletRequest): AccessTokenPrincipal? {
            val tokenString = request.getHeader("X-Forwarded-Access-Token") ?: run {
                val headerValue: String? = request.getHeader("Authorization")
                val prefix = "Bearer "
                if (headerValue?.startsWith(prefix) == true) {
                    headerValue.drop(prefix.length)
                } else {
                    null
                }
            } ?: return null
            return JWT.decode(tokenString).nullIfInvalid()?.let { AccessTokenPrincipal(it) }
        }
    }
}
