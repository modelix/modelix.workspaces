package org.modelix.services.workspaces

import io.kubernetes.client.custom.Quantity
import kotlinx.serialization.json.Json
import org.modelix.model.persistent.HashUtil
import org.modelix.services.workspaces.stubs.models.WorkspaceConfig

fun WorkspaceConfig.hash(): String = HashUtil.sha256(Json.encodeToString(this))

fun WorkspaceConfig.normalizeForBuild() = copy(
    name = "",
    memoryLimit = "",
    runConfig = null,
)

fun WorkspaceConfig.hashForBuild(): String = normalizeForBuild().hash()

fun String.toValidImageTag() = replace("*", "")

fun WorkspaceConfig.merge(other: WorkspaceConfig) = copy(
    name = other.name.takeIf { it.isNotEmpty() } ?: name,
    mpsVersion = other.validMPSVersion() ?: mpsVersion,
    memoryLimit = other.validMemoryLimit() ?: memoryLimit,
    gitRepositoryIds = other.gitRepositoryIds ?: gitRepositoryIds,
    mavenRepositories = other.mavenRepositories ?: mavenRepositories,
    mavenArtifacts = other.mavenArtifacts ?: mavenArtifacts,
    buildConfig = other.buildConfig ?: buildConfig,
    runConfig = other.runConfig ?: runConfig,
)

fun WorkspaceConfig.validMPSVersion() = mpsVersion.takeIf { it.isNotEmpty() }
fun WorkspaceConfig.validMemoryLimit() = runCatching { Quantity.fromString(memoryLimit).toSuffixedString() }.getOrNull()
