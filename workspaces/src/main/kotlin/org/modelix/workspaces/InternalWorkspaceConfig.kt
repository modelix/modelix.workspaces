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

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.modelix.model.persistent.HashUtil

const val DEFAULT_MPS_VERSION = "2024.1"

@Serializable
data class InternalWorkspaceConfig(
    val id: String,
    val name: String? = null,
    val mpsVersion: String? = null,
    val memoryLimit: String = "2Gi",
    val modelRepositories: List<ModelRepository> = listOf(),
    val gitRepositoryIds: List<String>? = null,
    val gitRepositories: List<GitRepository> = listOf(),
    val mavenRepositories: List<MavenRepository> = listOf(),
    val mavenDependencies: List<String> = listOf(),
    val uploads: List<String> = ArrayList(),
    val ignoredModules: List<String> = ArrayList(),
    val additionalGenerationDependencies: List<GenerationDependency> = ArrayList(),
    val loadUsedModulesOnly: Boolean = false,
    val sharedInstances: List<SharedInstance> = emptyList(),
    @Deprecated("Replaced by query parameter")
    val waitForIndexer: Boolean = true,
    val modelSyncEnabled: Boolean = false,
) {
    fun uploadIds() = uploads.map { UploadId(it) }
    fun toYaml() = Yaml.default.encodeToString(this).replace("\nwaitForIndexer: false", "").replace("\nwaitForIndexer: true", "")

    fun normalizeForBuild() = copy(
        name = null,
        mpsVersion = mpsVersion ?: DEFAULT_MPS_VERSION,
        memoryLimit = "2Gi",
        modelRepositories = emptyList(),
        gitRepositories = gitRepositories.map { it.copy(credentials = null) },
        loadUsedModulesOnly = false,
        sharedInstances = emptyList(),
        waitForIndexer = true,
        modelSyncEnabled = false,
    )
}

/**
 * The value of Workspace.hash() depends on the JSON serialization, which is not guaranteed to be always the same.
 * If the workspace data was loaded by using the hash then this original hash should be used instead of recomputing it.
 * There was an issue in the communication between the workspace-job and the workspace-manager,
 * because of a hash mismatch.
 */
data class WorkspaceAndHash(val workspace: InternalWorkspaceConfig, private val hash: WorkspaceHash) {
    fun hash(): WorkspaceHash = hash
    fun uploadIds() = workspace.uploadIds()

    val userDefinedOrDefaultMpsVersion
        get() = workspace.mpsVersion ?: DEFAULT_MPS_VERSION

    val id = workspace.id
    val name = workspace.name
    val mpsVersion = workspace.mpsVersion
    val memoryLimit = workspace.memoryLimit
    val modelRepositories = workspace.modelRepositories
    val gitRepositories = workspace.gitRepositories
    val mavenRepositories = workspace.mavenRepositories
    val mavenDependencies = workspace.mavenDependencies
    val uploads = workspace.uploads
    val ignoredModules = workspace.ignoredModules
    val additionalGenerationDependencies = workspace.additionalGenerationDependencies
    val loadUsedModulesOnly = workspace.loadUsedModulesOnly
    val sharedInstances = workspace.sharedInstances
}

fun InternalWorkspaceConfig.withHash(hash: WorkspaceHash) = WorkspaceAndHash(this, hash)
fun InternalWorkspaceConfig.withHash() = WorkspaceAndHash(this, WorkspaceHash(HashUtil.sha256(Json.encodeToString(this))))

@Serializable
data class GenerationDependency(val from: String, val to: String)

@Serializable
@JvmInline
value class WorkspaceHash(val hash: String) {
    init {
        require(HashUtil.isSha256(hash)) { "Not a hash: $hash" }
    }
    override fun toString(): String {
        return hash
    }

    fun toValidImageTag(): String {
        return hash.replace("*", "")
    }
}

@Serializable
data class ModelRepository(
    val id: String,
    val bindings: List<Binding> = listOf(),
)

@Serializable
data class Binding(
    val project: String? = null,
    val module: String? = null,
)

@Serializable
data class GitRepository(
    val url: String,
    val name: String? = null,
    val branch: String = "master",
    val commitHash: String? = null,
    val paths: List<String> = listOf(),
    val credentials: Credentials? = null,
)

@Serializable
data class Credentials(val user: String, val password: String)

@Serializable
data class MavenRepository(val url: String)

@Serializable
data class SharedInstance(val name: String = "shared", val allowWrite: Boolean = false)
