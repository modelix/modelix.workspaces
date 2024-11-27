
description = "Allows multiple clients to work on the same set of modules from different sources"

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

val mpsPlugins by configurations.registering

dependencies {
    implementation(libs.bundles.ktor.server)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.kubernetes.client)
    implementation(libs.jgit)
    implementation(libs.maven.invoker)
    implementation(libs.zt.zip)
    implementation(libs.commons.text)
    implementation(libs.jasypt)
    implementation(libs.modelix.model.client)
    implementation(libs.modelix.model.server)
    implementation(libs.ktor.client.cio)
    implementation(project(":workspaces"))
    implementation(project(":gitui"))
    implementation(libs.modelix.authorization)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    mpsPlugins(libs.modelix.mpsPlugins.legacySync)
    mpsPlugins(libs.modelix.mpsPlugins.diff)
    mpsPlugins(libs.modelix.mpsPlugins.generator)
    mpsPlugins(project(":workspace-client-plugin", configuration = "archives"))
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks.named("assemble") {
    dependsOn("installDist")
}

val copyClient = tasks.register("copyClient", Sync::class.java) {
    dependsOn(project(":workspace-client").tasks.named("distTar"))
    dependsOn(project(":workspace-job").tasks.named("distTar"))
    dependsOn(":workspace-client-plugin:buildPlugin")

    from(project(":workspace-client").tasks.distTar.map { it.archiveFile })
    from(project(":workspace-client").layout.projectDirectory.file("pre-startup.sh"))
    from(project(":workspace-job").tasks.distTar.map { it.archiveFile })
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
