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
import org.modelix.authorization.permissions.FileSystemAccessControlPersistence
import org.modelix.model.lazy.RepositoryId
import org.modelix.model.persistent.SerializationUtil
import org.modelix.model.server.ModelServerPermissionSchema
import org.modelix.workspaces.ModelServerWorkspacePersistence
import org.modelix.workspaces.UploadId
import org.modelix.workspaces.Workspace
import org.modelix.workspaces.WorkspaceHash
import org.modelix.workspaces.WorkspacePersistence
import org.modelix.workspaces.WorkspacesPermissionSchema
import org.modelix.workspaces.withHash
import java.io.File

class WorkspaceManager(private val credentialsEncryption: CredentialsEncryption) {
    val jwtUtil = ModelixJWTUtil().also { it.loadKeysFromEnvironment() }
    private val persistenceFile = File(System.getenv("WORKSPACES_DB_FILE") ?: "/workspace-manager/config/workspaces.json")
    val accessControlPersistence = FileSystemAccessControlPersistence(persistenceFile.parentFile.resolve("permissions.json"))
    val workspacePersistence: WorkspacePersistence = FileSystemWorkspacePersistence(persistenceFile)
    private val directory: File = run {
        // The workspace will contain git repositories. Avoid cloning them into an existing repository.
        val ancestors = mutableListOf(File(".").absoluteFile)
        while (ancestors.last().parentFile != null) ancestors += ancestors.last().parentFile
        val parentRepoDir = ancestors.lastOrNull { File(it, ".git").exists() }
        val workspacesDir = if (parentRepoDir != null) File(parentRepoDir.parent, "modelix-workspaces") else File("modelix-workspaces")
        workspacesDir.absoluteFile
    }
    val workspaceJobTokenGenerator: (Workspace) -> String = { workspace ->
        jwtUtil.createAccessToken(
            "workspace-job@modelix.org",
            listOf(
                WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.read.fullId,
                WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.readCredentials.fullId,
                WorkspacesPermissionSchema.workspaces.workspace(workspace.id).buildResult.write.fullId,
            ) + workspace.uploads.map { uploadId -> WorkspacesPermissionSchema.workspaces.uploads.upload(uploadId).read.fullId },
        )
    }
    val buildJobs = WorkspaceJobQueue(tokenGenerator = workspaceJobTokenGenerator)
    val kestraClient = KestraClient(jwtUtil)

    init {
        println("workspaces directory: $directory")

        // migrate existing workspaces from model-server persistence to file system persistence
        if (!(persistenceFile.exists())) {
            val legacyWorkspacePersistence: WorkspacePersistence = ModelServerWorkspacePersistence({
                jwtUtil.createAccessToken(
                    "workspace-manager@modelix.org",
                    listOf(
                        "legacy-user-defined-entries/write",
                        "legacy-user-defined-entries/read",
                        "legacy-global-objects/add",
                        "legacy-global-objects/read",
                    ),
                )
            })
            for (id in legacyWorkspacePersistence.getWorkspaceIds()) {
                val ws = legacyWorkspacePersistence.getWorkspaceForId(id) ?: continue
                workspacePersistence.update(ws)
            }
        }
    }

    @Synchronized
    fun update(workspace: Workspace): WorkspaceHash {
        val workspaceWithEncryptedCredentials = credentialsEncryption.copyWithEncryptedCredentials(workspace)
        val hash = workspacePersistence.update(workspaceWithEncryptedCredentials)
        synchronized(buildJobs) {
            buildJobs.removeByWorkspaceId(workspace.id)
        }
        return hash
    }

    fun getWorkspaceDirectory(workspace: Workspace) = File(directory, workspace.id)

    fun newUploadFolder(): File {
        val existingFolders = getUploadsFolder().listFiles()?.toList() ?: emptyList()
        val maxExistingId = existingFolders.map { SerializationUtil.longFromHex(it.name) }.maxOrNull() ?: 0
        val newId = UploadId(SerializationUtil.longToHex(maxExistingId + 1))
        val folder = getUploadFolder(newId)
        folder.mkdirs()
        return folder
    }

    private fun getUploadsFolder() = File(directory, "uploads")

    fun getExistingUploads(): List<File> = getUploadsFolder().listFiles()?.toList() ?: listOf()

    fun getUploadFolder(id: UploadId) = File(getUploadsFolder(), id.id)

    fun deleteUpload(id: UploadId) {
        val folder = getUploadFolder(id)
        if (folder.exists()) {
            folder.deleteRecursively()
        }
    }

    fun buildWorkspaceDownloadFileAsync(workspaceHash: WorkspaceHash): WorkspaceJobQueue.Job {
        val workspace = requireNotNull(workspacePersistence.getWorkspaceForHash(workspaceHash)) { "Workspace not found: $workspaceHash" }
        return buildJobs.getOrCreateJob(workspace)
    }

    fun rebuild(workspaceHash: WorkspaceHash): WorkspaceJobQueue.Job {
        val workspace = requireNotNull(workspacePersistence.getWorkspaceForHash(workspaceHash)) { "Workspace not found: $workspaceHash" }
        return synchronized(buildJobs) {
            buildJobs.removeByWorkspaceId(workspace.id)
            buildJobs.getOrCreateJob(workspace)
        }
    }

    fun getAllWorkspaces() = workspacePersistence.getAllWorkspaces()
    fun getWorkspaceIds() = workspacePersistence.getWorkspaceIds()
    fun getWorkspaceForId(workspaceId: String) = workspacePersistence.getWorkspaceForId(workspaceId)?.withHash()
    fun getWorkspaceForHash(workspaceHash: WorkspaceHash) = workspacePersistence.getWorkspaceForHash(workspaceHash)
    fun newWorkspace(owner: String?): Workspace {
        val newWorkspace = workspacePersistence.newWorkspace()
        if (owner != null) {
            accessControlPersistence.update { data ->
                data.withGrantToUser(owner, WorkspacesPermissionSchema.workspaces.workspace(newWorkspace.id).owner.fullId)
            }
        }
        return newWorkspace
    }
    fun removeWorkspace(workspaceId: String) = workspacePersistence.removeWorkspace(workspaceId)

    suspend fun enqueueGitImport(workspaceId: String): List<String> {
        kestraClient.updateGitImportFlow()
        val workspace = requireNotNull(getWorkspaceForId(workspaceId)) { "Workspace not found: $workspaceId" }
        val existingExecutions = kestraClient.getRunningImportJobIds(workspaceId)
        if (existingExecutions.isNotEmpty()) {
            return existingExecutions
        }
        return kestraClient.enqueueGitImport(credentialsEncryption.copyWithDecryptedCredentials(workspace.workspace))["id"]!!.jsonPrimitive.content.let { listOf(it) }
    }
}

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

    suspend fun enqueueGitImport(workspace: Workspace): JsonObject {
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
            HttpStatusCode.OK -> {}
            HttpStatusCode.NotFound -> {
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
