package org.modelix.workspaces

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.modelix.model.persistent.HashUtil

@Serializable
data class WorkspaceConfigForBuild(
    val id: String,
    val mpsVersion: String,
    val gitRepositories: Set<GitConfigForBuild>,
    val memoryLimit: Long,
    val mavenRepositories: Set<MavenRepositoryForBuild>,
    val mavenArtifacts: Set<String>,
    val ignoredModules: Set<String>,
    val additionalGenerationDependencies: Set<Pair<String, String>>,
    val loadUsedModulesOnly: Boolean,
)

@Serializable
data class GitConfigForBuild(
    val url: String,
    val username: String?,
    val password: String?,
    val branch: String,
    val commitHash: String,
)

@Serializable
data class MavenRepositoryForBuild(
    val url: String,
    val username: String?,
    val password: String?,
)

fun WorkspaceConfigForBuild.hash(): String = HashUtil.sha256(Json.encodeToString(this))
