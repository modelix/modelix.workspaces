rootProject.name = "modelix.workspaces"

pluginManagement {
    val kotlinVersion: String by settings
    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("multiplatform") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
    }
}

include("instances-manager")
include("workspaces")
include("workspace-manager")
include("workspace-client")
include("headless-mps")
include("gitui")
