package org.modelix.workspace.manager

import kotlinx.serialization.Serializable
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.authorization.permissions.FileSystemAccessControlPersistence
import org.modelix.model.persistent.SerializationUtil
import org.modelix.services.workspaces.FileSystemPersistence
import org.modelix.services.workspaces.PersistedState
import org.modelix.services.workspaces.stubs.models.WorkspaceConfig
import org.modelix.workspaces.InternalWorkspaceConfig
import org.modelix.workspaces.UploadId
import org.modelix.workspaces.WorkspaceConfigForBuild
import org.modelix.workspaces.WorkspacesPermissionSchema
import java.io.File

@Serializable
data class WorkspaceManagerData(val workspaces: Map<String, WorkspaceConfig> = emptyMap())

class WorkspaceManager(val credentialsEncryption: CredentialsEncryption) {
    val jwtUtil = ModelixJWTUtil().also { it.loadKeysFromEnvironment() }
    val data: SharedMutableState<WorkspaceManagerData> = PersistedState(
        persistence = FileSystemPersistence(
            file = File("/workspace-manager/config/workspaces-v2.json"),
            serializer = WorkspaceManagerData.serializer(),
        ),
        defaultState = { WorkspaceManagerData() },
    ).state
    val accessControlPersistence = FileSystemAccessControlPersistence(File("/workspace-manager/config/permissions.json"))
    private val directory: File = run {
        // The workspace will contain git repositories. Avoid cloning them into an existing repository.
        val ancestors = mutableListOf(File(".").absoluteFile)
        while (ancestors.last().parentFile != null) ancestors += ancestors.last().parentFile
        val parentRepoDir = ancestors.lastOrNull { File(it, ".git").exists() }
        val workspacesDir = if (parentRepoDir != null) File(parentRepoDir.parent, "modelix-workspaces") else File("modelix-workspaces")
        workspacesDir.absoluteFile
    }
    val workspaceJobTokenGenerator: (WorkspaceConfigForBuild) -> String = { workspace ->
        jwtUtil.createAccessToken(
            "workspace-job@modelix.org",
            listOf(
                WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.read.fullId,
                WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.readCredentials.fullId,
                WorkspacesPermissionSchema.workspaces.workspace(workspace.id).buildResult.write.fullId,
            )/* + workspace.uploads.map { uploadId -> WorkspacesPermissionSchema.workspaces.uploads.upload(uploadId).read.fullId }*/,
        )
    }
//    val buildJobs = WorkspaceJobQueue(tokenGenerator = workspaceJobTokenGenerator)
    val kestraClient = KestraClient(jwtUtil)

    fun updateWorkspace(workspaceId: String, updater: (WorkspaceConfig) -> WorkspaceConfig): WorkspaceConfig {
        return data.update {
            it.copy(
                workspaces = it.workspaces + (workspaceId to updater(it.workspaces.getValue(workspaceId))),
            )
        }.workspaces.getValue(workspaceId)
    }

    fun putWorkspace(workspace: WorkspaceConfig) {
        require(workspace.id.isNotBlank())
        data.update {
            it.copy(workspaces = it.workspaces + (workspace.id to workspace))
        }
    }

    fun getWorkspaceDirectory(workspace: InternalWorkspaceConfig) = File(directory, workspace.id)

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

      fun getAllWorkspaces() = data.getValue().workspaces.values.toList()
//    fun getWorkspaceIds() = workspacePersistence.getWorkspaceIds()
      fun getWorkspace(workspaceId: String) = data.getValue().workspaces[workspaceId]
//    fun getWorkspaceForHash(workspaceHash: WorkspaceHash) = workspacePersistence.getWorkspaceForHash(workspaceHash)
//    fun newWorkspace(owner: String?): InternalWorkspaceConfig {
//        val newWorkspace = workspacePersistence.newWorkspace()
//        if (owner != null) {
//            accessControlPersistence.update { data ->
//                data.withGrantToUser(owner, WorkspacesPermissionSchema.workspaces.workspace(newWorkspace.id).owner.fullId)
//            }
//        }
//        return newWorkspace
//    }

    fun assignOwner(workspaceId: String, owner: String) {
        accessControlPersistence.update { data ->
            data.withGrantToUser(owner, WorkspacesPermissionSchema.workspaces.workspace(workspaceId).owner.fullId)
        }
    }

    fun removeWorkspace(workspaceId: String) {
        data.update { it.copy(workspaces = it.workspaces - workspaceId) }
    }

//    suspend fun enqueueGitImport(workspaceId: String): List<String> {
//        kestraClient.updateGitImportFlow()
//        val workspace = requireNotNull(getWorkspaceForId(workspaceId)) { "Workspace not found: $workspaceId" }
//        val existingExecutions = kestraClient.getRunningImportJobIds(workspaceId)
//        if (existingExecutions.isNotEmpty()) {
//            return existingExecutions
//        }
//        return kestraClient.enqueueGitImport(credentialsEncryption.copyWithDecryptedCredentials(workspace.workspace))["id"]!!.jsonPrimitive.content.let { listOf(it) }
//    }
}

