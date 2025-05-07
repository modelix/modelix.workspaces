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
import io.ktor.server.auth.jwt.JWTPrincipal
import org.modelix.authorization.nullIfInvalid
import java.util.UUID
import javax.servlet.http.HttpServletRequest

class RedirectedURL(
    val targetPath: String,
    val targetPort: Int,
    val instanceId: UUID,
    val userToken: JWTPrincipal?,
    var targetHost: String? = null,
) {

    fun getURLToRedirectTo(websocket: Boolean): String? {
        var url = (if (websocket) "ws" else "http") + "://"
        url += "$targetHost:$targetPort$targetPath"
        return url
    }

    companion object {
        fun redirect(request: HttpServletRequest): RedirectedURL? {
            var remainingPath = request.requestURI
            remainingPath = remainingPath.trimStart('/')

            val instanceId = remainingPath.substringBefore('/').let { UUID.fromString(it) }
            remainingPath = remainingPath.substringAfter('/', "")

            if (!remainingPath.startsWith("port/")) return null
            remainingPath = remainingPath.substringAfter("port/")
            val port = remainingPath.substringBefore('/').toIntOrNull()?.takeIf { (0..65535).contains(it) } ?: return null
            remainingPath = remainingPath.substringAfter('/', "")

            if (request.queryString != null) remainingPath += "?" + request.queryString

            return RedirectedURL(
                targetPath = "/$remainingPath",
                targetPort = port,
                instanceId = instanceId,
                userToken = getUserIdFromAuthHeader(request),
            )
        }

        private fun getUserIdFromAuthHeader(request: HttpServletRequest): JWTPrincipal? {
            val tokenString = run {
                val headerValue: String? = request.getHeader("Authorization")
                val prefix = "Bearer "
                if (headerValue?.startsWith(prefix) == true) {
                    headerValue.drop(prefix.length)
                } else {
                    null
                }
            } ?: return null
            return JWT.decode(tokenString).nullIfInvalid()?.let { JWTPrincipal(it) }
        }
    }
}
