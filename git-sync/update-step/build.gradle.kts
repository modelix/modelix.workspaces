plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

application {
    mainClass.set("org.modelix.workspace.gitsync.update.MainKt")
}

val mpsDependencies by configurations.creating

dependencies {
    implementation(libs.modelix.mps.build.tools)
    // TODO MODELIX-597 libs.versions.toml
    implementation("org.apache.ant:ant:1.10.14")

    mpsDependencies(libs.modelix.bulk.model.sync.mps)
}

val mpsDependenciesDir = layout.buildDirectory.dir("mpsDependencies")
val extractMpsDependencies by tasks.registering(Copy::class) {
    from(mpsDependencies.resolve())
    into(mpsDependenciesDir)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

val mpsMajorVersions = providers.gradleProperty("mpsMajorVersions").get().split(",")

val buildImages by tasks.registering {
}

mpsMajorVersions.forEach { mpsMajorVersion ->
    val buildMPSSpecificImage = tasks.register("buildImage$mpsMajorVersion", Exec::class) {
        dependsOn(tasks.installDist)
        environment(
            "MODELIX_WORKSPACES_VERSION" to rootProject.version,
            "MPS_MAJOR_VERSION" to mpsMajorVersion
        )
        environment("MODELIX_WORKSPACES_VERSION" to rootProject.version)
        commandLine = listOf("sh", "-c", "./docker-build.sh")
        dependsOn(":resolveMps$mpsMajorVersion")
        dependsOn(extractMpsDependencies)
    }
    buildImages {
        dependsOn(buildMPSSpecificImage)
    }
}