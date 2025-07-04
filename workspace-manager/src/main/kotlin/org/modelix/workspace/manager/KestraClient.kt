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
import org.modelix.model.lazy.BranchReference
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
        return getRunningImportJobIds(mapOf("workspace" to workspaceId))
    }

    suspend fun getRunningImportJobIds(labels: Map<String, String>): List<String> {
        val responseObject: JsonObject = httpClient.get {
            url {
                takeFrom(kestraApiEndpoint)
                appendPathSegments("executions", "search")
                parameters.append("namespace", "modelix")
                parameters.append("flowId", "git_import")
                parameters.appendAll("labels", labels.map { "${it.key}:${it.value}" })
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
        return enqueueGitImport(
            gitRepoUrl = gitRepo.url,
            gitUser = gitRepo.credentials?.user,
            gitPassword = gitRepo.credentials?.password,
            gitRevision = "origin/${gitRepo.branch}",
            modelixBranch = RepositoryId("workspace_${workspace.id}").getBranchReference("git-import"),
            labels = mapOf("workspace" to workspace.id),
        )
    }

    suspend fun enqueueGitImport(
        gitRepoUrl: String,
        gitUser: String?,
        gitPassword: String?,
        gitRevision: String,
        modelixBranch: BranchReference,
        labels: Map<String, String>,
    ): JsonObject {
        updateGitImportFlow()

        val token = jwtUtil.createAccessToken(
            "git-import@modelix.org",
            listOf(
                ModelServerPermissionSchema.repository(modelixBranch.repositoryId).create.fullId,
                ModelServerPermissionSchema.branch(modelixBranch).rewrite.fullId,
            ),
        )

        val response = httpClient.post {
            url {
                takeFrom(kestraApiEndpoint)
                appendPathSegments("executions", "modelix", "git_import")
                if (labels.isNotEmpty()) {
                    parameters.appendAll("labels", labels.map { "${it.key}:${it.value}" })
                }
            }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("git_url", gitRepoUrl)
                        append("git_revision", gitRevision)
                        append("git_limit", "50")
                        append("modelix_repo_name", modelixBranch.repositoryId.id)
                        append("modelix_target_branch", modelixBranch.branchName)
                        append("token", token)
                        gitUser?.let { append("git_user", it) }
                        gitPassword?.let { append("git_pw", it) }
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
