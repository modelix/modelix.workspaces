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

mpsMajorVersions.forEach { mpsMajorVersion ->
    val targetDir = layout.buildDirectory.dir("mps$mpsMajorVersion")

    val mps = configurations.create("mps$mpsMajorVersion")
    val mpsVersion = providers.gradleProperty("mpsVersion$mpsMajorVersion").get()

    dependencies {
        mps("com.jetbrains:mps:$mpsVersion")
    }

    val resolveMps = tasks.register("resolveMps$mpsMajorVersion", Sync::class) {
        from(mps.resolve().map { zipTree(it) })
        into(targetDir)
    }

    val deleteMps = tasks.register("deleteMps$mpsMajorVersion", Delete::class) {
        delete(targetDir)
    }
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks.named("assemble") {
    dependsOn("installDist")
}