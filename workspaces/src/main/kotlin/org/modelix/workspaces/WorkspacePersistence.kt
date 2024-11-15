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
package org.modelix.workspaces

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.modelix.model.client.RestWebModelClient
import org.modelix.model.persistent.HashUtil
import org.modelix.model.persistent.SerializationUtil

interface WorkspacePersistence {
    fun getWorkspaceIds(): Set<String>
    fun newWorkspace(): Workspace
    fun removeWorkspace(workspaceId: String)
    fun getAllWorkspaces(): List<Workspace>
    fun getWorkspaceForId(id: String): Workspace?
    fun getWorkspaceForHash(hash: WorkspaceHash): WorkspaceAndHash?
    fun update(workspace: Workspace): WorkspaceHash
    fun getAccessControlData(): WorkspacesAccessControlData
    fun updateAccessControlData(updater: (WorkspacesAccessControlData) -> WorkspacesAccessControlData)
}

class ModelServerWorkspacePersistence(authTokenProvider: () -> String?) : WorkspacePersistence {
    private val WORKSPACE_LIST_KEY = "workspaces"
    private val modelClient: RestWebModelClient = RestWebModelClient(getModelServerUrl(), authTokenProvider = authTokenProvider)

    fun generateId(): String = SerializationUtil.longToHex(modelClient.idGenerator.generate())

    override fun getWorkspaceIds(): Set<String> {
        val idString = modelClient.get(WORKSPACE_LIST_KEY)
        if (idString.isNullOrEmpty()) return setOf()
        return idString.split(",").toSet()
    }

    override fun getAccessControlData(): WorkspacesAccessControlData {
        return WorkspacesAccessControlData()
    }

    override fun updateAccessControlData(updater: (WorkspacesAccessControlData) -> WorkspacesAccessControlData) {
        throw UnsupportedOperationException()
    }

    private fun setWorkspaceIds(ids: Set<String>) {
        modelClient.put(WORKSPACE_LIST_KEY, ids.sorted().joinToString(","))
    }

    @Synchronized
    override fun newWorkspace(): Workspace {
        val workspace = Workspace(
            id = generateId(),
            modelRepositories = listOf(ModelRepository(id = "default"))
        )
        modelClient.put(key(workspace.id), Json.encodeToString(workspace))
        setWorkspaceIds(getWorkspaceIds() + workspace.id)
        return workspace
    }

    @Synchronized
    override fun removeWorkspace(workspaceId: String) {
        setWorkspaceIds(getWorkspaceIds() - workspaceId)
    }

    private fun key(workspaceId: String) = "workspace-$workspaceId"

    override fun getWorkspaceForId(id: String): Workspace? {
        require(id.matches(Regex("[a-f0-9]{9,16}"))) { "Invalid workspace ID: $id" }
        return getWorkspaceForIdOrHash(id)?.workspace
    }

    override fun getAllWorkspaces(): List<Workspace> {
        return getWorkspaceIds().mapNotNull { getWorkspaceForId(it) }
    }

    @Synchronized
    fun getWorkspaceForIdOrHash(idOrHash: String): WorkspaceAndHash? {
        val hash: WorkspaceHash
        val json: String
        if (HashUtil.isSha256(idOrHash)) {
            hash = WorkspaceHash(idOrHash)
            json = modelClient.get(hash.toString()) ?: return null
        } else {
            val id = idOrHash
            require(id.matches(Regex("[a-f0-9]{9,16}"))) { "Invalid workspace ID: $id" }

            val hashOrJson = modelClient.get(key(id)) ?: return null
            if (HashUtil.isSha256(hashOrJson)) {
                hash = WorkspaceHash(hashOrJson)
                json = modelClient.get(hash.toString()) ?: return null
            } else {
                // migrate old entry
                json = hashOrJson
                hash = WorkspaceHash(HashUtil.sha256(json))
                modelClient.put(hash.toString(), json)
                modelClient.put(key(id), hash.toString())
            }
        }
        return Json.decodeFromString<Workspace>(json).withHash(hash)
    }

    @Synchronized
    override fun getWorkspaceForHash(hash: WorkspaceHash): WorkspaceAndHash? {
        val json = modelClient.get(hash.toString()) ?: return null
        return Json.decodeFromString<Workspace>(json).withHash(hash)
    }

    @Synchronized
    override fun update(workspace: Workspace): WorkspaceHash {
        val mpsVersion = workspace.mpsVersion
        require(mpsVersion == null || mpsVersion.matches(Regex("""20\d\d\.\d"""))) {
            "Invalid major MPS version: '$mpsVersion'. Examples for valid values: '2020.3', '2021.1', '2021.2'."
        }
        val id = workspace.id
        val json = Json.encodeToString(workspace)
        val hash = WorkspaceHash(HashUtil.sha256(json))
        modelClient.put(hash.toString(), json)
        modelClient.put(key(id), hash.toString())
        setWorkspaceIds(getWorkspaceIds() + workspace.id)
        return hash
    }

    fun dispose() {
        modelClient.dispose()
    }

    fun getModelServerUrl(): String {
        return listOf("model.server.url", "model_server_url")
            .flatMap { listOf(System.getProperty(it), System.getenv(it)) }
            .filterNotNull()
            .firstOrNull() ?: "http://localhost:28101/"
    }
}