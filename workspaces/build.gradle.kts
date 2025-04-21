plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(11) // must be compatible to workspace-job
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.jgit)
    implementation(libs.maven.invoker)
    implementation(libs.zt.zip)
    implementation(libs.commons.text)
    implementation(libs.jasypt)
    implementation(libs.modelix.model.client)
    implementation(libs.modelix.authorization)
    testImplementation(kotlin("test"))
    testImplementation(libs.modelix.authorization)
}
