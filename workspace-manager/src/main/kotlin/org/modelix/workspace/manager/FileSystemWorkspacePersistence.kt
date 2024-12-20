package org.modelix.workspace.manager

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.modelix.model.persistent.SerializationUtil
import org.modelix.workspaces.ModelRepository
import org.modelix.workspaces.Workspace
import org.modelix.workspaces.WorkspaceAndHash
import org.modelix.workspaces.WorkspaceHash
import org.modelix.workspaces.WorkspacePersistence
import org.modelix.workspaces.withHash
import java.io.File
import kotlin.math.max

@Serializable
private data class WorkspacesDB(
    val lastUsedWorkspaceId: Long = 0L, // for preventing reuse after delete
    val workspaces: Map<String, Workspace> = emptyMap(),
    val workspacesByHash: Map<WorkspaceHash, Workspace> = emptyMap(),
)

class FileSystemWorkspacePersistence(val file: File) : WorkspacePersistence {

    private var db: WorkspacesDB = if (file.exists()) {
        Json.decodeFromString<WorkspacesDB>(file.readText()).let { db ->
            val maxExisting: Long = (db.workspaces.keys.asSequence() + db.workspacesByHash.values.asSequence().map { it.id })
                .map { SerializationUtil.longFromHex(it) }.maxOrNull() ?: 0
            db.copy(lastUsedWorkspaceId = max(db.lastUsedWorkspaceId, maxExisting))
        }
    } else {
        WorkspacesDB()
    }

    @Synchronized
    private fun newWorkspaceId(): String {
        return SerializationUtil.longToHex(db.lastUsedWorkspaceId + 1)
    }

    override fun getWorkspaceIds(): Set<String> {
        return db.workspaces.keys
    }

    override fun getAllWorkspaces(): List<Workspace> {
        return db.workspaces.values.toList()
    }

    @Synchronized
    override fun newWorkspace(): Workspace {
        val workspace = Workspace(
            id = newWorkspaceId(),
            modelRepositories = listOf(ModelRepository(id = "default")),
        )
        update(workspace)
        return workspace
    }

    @Synchronized
    override fun removeWorkspace(workspaceId: String) {
        db = db.copy(workspaces = db.workspaces - workspaceId)
        writeDBFile()
    }

    override fun getWorkspaceForId(id: String): Workspace? {
        return db.workspaces[id]
    }

    override fun getWorkspaceForHash(hash: WorkspaceHash): WorkspaceAndHash? {
        return db.workspacesByHash[hash]?.withHash(hash)
    }

    @Synchronized
    override fun update(workspace: Workspace): WorkspaceHash {
        val hash = workspace.withHash().hash()
        db = db.copy(
            lastUsedWorkspaceId = max(db.lastUsedWorkspaceId, workspace.id.toLong(16)),
            workspaces = db.workspaces + (workspace.id to workspace),
            workspacesByHash = db.workspacesByHash + (hash to workspace),
        )
        writeDBFile()
        return hash
    }

    private fun writeDBFile() {
        file.writeText(Json.encodeToString(db))
    }
}
