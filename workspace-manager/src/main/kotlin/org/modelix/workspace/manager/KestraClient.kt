package org.modelix.workspace.manager

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.util.url
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.model.lazy.RepositoryId
import org.modelix.model.server.ModelServerPermissionSchema
import org.modelix.workspaces.InternalWorkspaceConfig

class KestraClient(val jwtUtil: ModelixJWTUtil) {
    private val kestraApiEndpoint = url {
        takeFrom(System.getenv("KESTRA_URL"))
        appendPathSegments("api", "v1")
    }

    private val httpClient = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) {
            json()
        }
    }

    suspend fun getRunningImportJobIds(workspaceId: String): List<String> {
        val responseObject: JsonObject = httpClient.get {
            url {
                takeFrom(kestraApiEndpoint)
                appendPathSegments("executions", "search")
                parameters.append("namespace", "modelix")
                parameters.append("flowId", "git_import")
                parameters.append("labels", "workspace:$workspaceId")
                parameters.append("state", "CREATED")
                parameters.append("state", "QUEUED")
                parameters.append("state", "RUNNING")
                parameters.append("state", "RETRYING")
                parameters.append("state", "PAUSED")
                parameters.append("state", "RESTARTED")
                parameters.append("state", "KILLING")
            }
        }.body()

        return responseObject["results"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    suspend fun enqueueGitImport(workspace: InternalWorkspaceConfig): JsonObject {
        val gitRepo = workspace.gitRepositories.first()

        updateGitImportFlow()

        val targetBranch = RepositoryId("workspace_${workspace.id}").getBranchReference("git-import")
        val token = jwtUtil.createAccessToken(
            "git-import@modelix.org",
            listOf(
                ModelServerPermissionSchema.repository(targetBranch.repositoryId).create.fullId,
                ModelServerPermissionSchema.branch(targetBranch).rewrite.fullId,
            ),
        )

        val response = httpClient.post {
            url {
                takeFrom(kestraApiEndpoint)
                appendPathSegments("executions", "modelix", "git_import")
                parameters["labels"] = "workspace:${workspace.id}"
            }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("git_url", gitRepo.url)
                        append("git_revision", "origin/${gitRepo.branch}")
                        append("modelix_repo_name", "workspace_${workspace.id}")
                        append("modelix_target_branch", "git-import")
                        append("token", token)
                        gitRepo.credentials?.also { credentials ->
                            append("git_user", credentials.user)
                            append("git_pw", credentials.password)
                        }
                    },
                ),
            )
        }

        return response.body()
    }

    suspend fun updateGitImportFlow() {
        // language=yaml
        val content = TextContent(
            """
                id: git_import
                namespace: modelix
                
                inputs:
                  - id: git_url
                    type: URI
                    required: true
                    defaults: https://github.com/coolya/Durchblick.git
                  - id: git_revision
                    type: STRING
                    defaults: HEAD
                  - id: modelix_repo_name
                    type: STRING
                    required: true
                  - id: modelix_target_branch
                    type: STRING
                    required: true
                    defaults: git-import
                  - id: token
                    type: SECRET
                    required: true
                  - type: SECRET
                    id: git_pw
                    required: false
                  - type: SECRET
                    id: git_user
                    required: false
                  - id: git_limit
                    type: INT
                    defaults: 200
                
                tasks:
                  - id: clone_and_import
                    type: io.kestra.plugin.kubernetes.PodCreate
                    namespace: ${System.getenv("KUBERNETES_NAMESPACE")}
                    spec:
                      containers:
                      - name: importer
                        image: ${System.getenv("GIT_IMPORT_IMAGE")}
                        args:
                          - git-import-remote
                          - "{{ inputs.git_url }}"
                          - --git-user
                          - "{{ inputs.git_user }}"
                          - --git-password
                          - "{{ inputs.git_pw }}"
                          - --limit
                          - "{{ inputs.git_limit }}"
                          - --model-server
                          - "${System.getenv("model_server_url")}"
                          - --token
                          - "{{ inputs.token }}"
                          - --repository
                          - "{{ inputs.modelix_repo_name }}"
                          - --branch
                          - "{{ inputs.modelix_target_branch }}"
                          - --rev
                          - "{{ inputs.git_revision }}"
                      restartPolicy: Never
            """.trimIndent(),
            ContentType("application", "x-yaml"),
        )

        val response = httpClient.put {
            expectSuccess = false
            url {
                takeFrom(kestraApiEndpoint)
                appendPathSegments("flows", "modelix", "git_import")
            }
            setBody(content)
        }
        when (response.status) {
            HttpStatusCode.Companion.OK -> {}
            HttpStatusCode.Companion.NotFound -> {
                httpClient.post {
                    url {
                        takeFrom(kestraApiEndpoint)
                        appendPathSegments("flows")
                    }
                    setBody(content)
                }
            }
            else -> {
                throw RuntimeException("${response.status}\n\n${response.bodyAsText()}\n\n${content.text}")
            }
        }
    }
}
