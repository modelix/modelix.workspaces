/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.modelix.workspace.job

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.modelix.workspaces.InternalWorkspaceConfig
import org.modelix.workspaces.WorkspaceBuildStatus
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val LOG = mu.KotlinLogging.logger("main")

fun main(args: Array<String>) {
    try {
        val buildTaskId = propertyOrEnv("modelix.workspace.task.id")?.let { UUID.fromString(it) }
            ?: throw RuntimeException("modelix.workspace.task.id not specified")

        var serverUrl = propertyOrEnv("modelix.workspace.server") ?: "http://workspace-manager:28104/"
        serverUrl = serverUrl.trimEnd('/')
        LOG.debug { "Workspace manager URL: $serverUrl" }

        val httpClient = HttpClient(CIO) {
            defaultRequest {
                bearerAuth(System.getenv("INITIAL_JWT_TOKEN"))
            }
            expectSuccess = true
            install(ContentNegotiation) {
                json()
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 1.minutes.inWholeMilliseconds
            }
        }

        runBlocking {
            printNewJobStatus(WorkspaceBuildStatus.Running)
            val workspace: InternalWorkspaceConfig = httpClient.get {
                url {
                    takeFrom(serverUrl)
                    appendPathSegments("modelix", "workspaces", "tasks", buildTaskId.toString(), "config")
                    parameter("decryptCredentials", "true")
                }
            }.body()
            val job = WorkspaceBuildJob(workspace, httpClient, serverUrl)
            job.buildWorkspace()
            // job.status = if (job.status == WorkspaceBuildStatus.FailedBuild) WorkspaceBuildStatus.ZipSuccessful else WorkspaceBuildStatus.AllSuccessful
        }
    } catch (ex: Throwable) {
        LOG.error(ex) { "" }
        printNewJobStatus(WorkspaceBuildStatus.FailedZip)
    }
}

fun printNewJobStatus(status: WorkspaceBuildStatus) {
    println("###${WorkspaceBuildStatus::class.simpleName} = $status###")
}

fun propertyOrEnv(key: String): String? {
    return listOf(key, key.replace(".", "_"))
        .flatMap { listOf(System.getProperty(it), System.getenv(it)) }
        .firstOrNull { !it.isNullOrEmpty() }
}
