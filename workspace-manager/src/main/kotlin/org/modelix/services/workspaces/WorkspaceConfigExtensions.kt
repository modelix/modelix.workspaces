package org.modelix.services.workspaces

import kotlinx.serialization.json.Json
import org.modelix.model.persistent.HashUtil
import org.modelix.services.workspaces.stubs.models.WorkspaceConfig

fun WorkspaceConfig.hash(): String = HashUtil.sha256(Json.encodeToString(this))

fun WorkspaceConfig.normalizeForBuild() = copy(
    name = "",
    memoryLimit = "",
    gitRepositories = gitRepositories.map { it.copy(credentials = null) },
    runConfig = null,
)

fun WorkspaceConfig.hashForBuild(): String = normalizeForBuild().hash()

fun String.toValidImageTag() = replace("*", "")
