plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("org.modelix.workspace.job.MainKt")
}

dependencies {
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.jgit)
    implementation(libs.maven.invoker)
    implementation(libs.zt.zip)

    implementation(project(":workspaces"))
    implementation(libs.modelix.mps.build.tools)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

val mpsMajorVersions = providers.gradleProperty("mpsMajorVersions").get().split(",")
val modelixMpsComponentsVersionWithoutMpsVersion: String by project

mpsMajorVersions.forEach { mpsMajorVersion ->
    val artifactsDir = layout.buildDirectory.dir("mps$mpsMajorVersion/artifacts")

    val mps = configurations.create("mps$mpsMajorVersion")
    val mpsVersion = providers.gradleProperty("mpsVersion$mpsMajorVersion").get()

    val mpsArtifacts = configurations.create("mpsArtifacts$mpsMajorVersion")
    val mpsExtensionsVersion = providers.gradleProperty("mpsExtensionsVersion$mpsMajorVersion").get()

    val modelixMpsComponents = configurations.create("modelixMpsComponents$mpsMajorVersion")
    val modelixMpsComponentsVersion = providers.gradleProperty("modelixMpsComponentsVersion$mpsMajorVersion").get()

    dependencies {
        mps("com.jetbrains:mps:$mpsVersion")
        mpsArtifacts("de.itemis.mps:extensions:$mpsExtensionsVersion")
        modelixMpsComponents("org.modelix.mps:build-solution:$modelixMpsComponentsVersion")
    }

    val resolveMps = tasks.register("resolveMps$mpsMajorVersion", Copy::class) {
        from(mps.resolve().map { zipTree(it) })
        into(artifactsDir.map { it.dir("mps") })
    }

    val resolveMpsArtifacts = tasks.register("resolveMpsArtifacts$mpsMajorVersion",Copy::class) {
        from(mpsArtifacts.resolve().map { zipTree(it) })
        into(artifactsDir)
    }

    val resolveModelixMpsComponents = tasks.register("resolveModelixMpsComponents$mpsMajorVersion",Copy::class) {
        from(modelixMpsComponents.resolve().map { zipTree(it) })
        into(artifactsDir.map { it.dir("org.modelix") })
    }

    tasks.assemble {
        dependsOn(resolveMps)
        dependsOn(resolveMpsArtifacts)
        dependsOn(resolveModelixMpsComponents)
    }
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks.named("assemble") {
    dependsOn("installDist")
}