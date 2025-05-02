
description = "Allows multiple clients to work on the same set of modules from different sources"

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
}

val mpsPlugins by configurations.registering {
    attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.ZIP_TYPE)
}

dependencies {
    implementation(libs.auth0.jwt)
    implementation(libs.bundles.ktor.server)
    implementation(libs.commons.codec)
    implementation(libs.commons.io)
    implementation(libs.commons.lang3)
    implementation(libs.commons.text)
    implementation(libs.jasypt)
    implementation(libs.jetty.proxy)
    implementation(libs.jetty.server)
    implementation(libs.jetty.servlet)
    implementation(libs.jetty.websocket.client)
    implementation(libs.jetty.websocket.server)
    implementation(libs.jetty.websocket.servlet)
    implementation(libs.jgit)
    implementation(libs.json)
    implementation(libs.kaml)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.netty)
    implementation(libs.kubernetes.client)
    implementation(libs.logback.classic)
    implementation(libs.maven.invoker)
    implementation(libs.modelix.authorization)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.modelix.model.client)
    implementation(libs.modelix.model.server) {
        isTransitive = false
    }
    implementation(libs.zt.zip)
    implementation(project(":gitui"))
    implementation(project(":workspaces"))
    implementation(libs.modelix.api.server.stubs)
    mpsPlugins(libs.bundles.modelix.mpsPlugins.all)
    mpsPlugins(project(":workspace-client-plugin", configuration = "archives"))
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}

val copyClient = tasks.register("copyClient", Sync::class.java) {
    dependsOn(project(":workspace-job").tasks.named("distTar"))
    dependsOn(":workspace-client-plugin:buildPlugin")

    from(project(":workspace-job").tasks.named<Tar>("distTar").map { it.archiveFile })
    from(mpsPlugins)
    into(project.layout.buildDirectory.dir("client/org/modelix/workspace/static"))
    rename { fileName ->
        // strip version number
        val artifact = mpsPlugins.get().resolvedConfiguration.resolvedArtifacts.find { it.file.name == fileName }
            ?: return@rename fileName
        artifact.name + "." + artifact.extension
    }
}

tasks.processResources {
    dependsOn(copyClient)
}

sourceSets {
    main {
        resources {
            srcDir(project.layout.buildDirectory.dir("client"))
        }
    }
}

jib {
    container {
        ports = listOf("28104")
    }
}
