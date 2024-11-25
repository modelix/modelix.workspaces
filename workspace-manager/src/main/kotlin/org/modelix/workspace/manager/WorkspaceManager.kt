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

import org.apache.commons.io.FileUtils
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.authorization.permissions.FileSystemAccessControlPersistence
import org.modelix.model.persistent.SerializationUtil
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
                WorkspacesPermissionSchema.workspaces.workspace(workspace.id).buildResult.write.fullId,
            ) + workspace.uploads.map { uploadId -> WorkspacesPermissionSchema.workspaces.uploads.upload(uploadId).read.fullId }
        )
    }
    private val buildJobs = WorkspaceJobQueue(tokenGenerator = workspaceJobTokenGenerator)

    init {
        println("workspaces directory: $directory")

        // migrate existing workspaces from model-server persistence to file system persistence
        if (!(persistenceFile.exists())) {
            val legacyWorkspacePersistence: WorkspacePersistence = ModelServerWorkspacePersistence({
                jwtUtil.createAccessToken("workspace-manager@modelix.org", listOf(
                    "legacy-user-defined-entries/write",
                    "legacy-user-defined-entries/read",
                    "legacy-global-objects/add",
                    "legacy-global-objects/read",
                ))
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
        val workspace = workspacePersistence.getWorkspaceForHash(workspaceHash) ?: throw RuntimeException("Workspace not found: $workspaceHash")
        return buildJobs.getOrCreateJob(workspace)
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
}
