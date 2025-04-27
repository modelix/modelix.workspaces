package org.modelix.workspace.manager

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.modelix.service.workspaces.controllers.ConnectivityMavenArtifactsController
import org.modelix.service.workspaces.controllers.ConnectivityMavenArtifactsController.Companion.connectivityMavenArtifactsRoutes
import org.modelix.service.workspaces.controllers.ConnectivityMavenController
import org.modelix.service.workspaces.controllers.ConnectivityMavenController.Companion.connectivityMavenRoutes
import org.modelix.service.workspaces.controllers.ConnectivityMavenRepositoriesController
import org.modelix.service.workspaces.controllers.ConnectivityMavenRepositoriesController.Companion.connectivityMavenRepositoriesRoutes
import org.modelix.service.workspaces.controllers.TypedApplicationCall
import org.modelix.service.workspaces.models.MavenArtifact
import org.modelix.service.workspaces.models.MavenArtifactList
import org.modelix.service.workspaces.models.MavenConnectorConfig
import org.modelix.service.workspaces.models.MavenRepository
import org.modelix.service.workspaces.models.MavenRepositoryList

class MavenControllerImpl :
    ConnectivityMavenRepositoriesController,
    ConnectivityMavenArtifactsController,
    ConnectivityMavenController {

    val data = MavenConnectorConfig(
        repositories = listOf(
            MavenRepository(id = "itemis", "https://artifacts.itemis.cloud/repository/maven-mps/"),
        ),
        artifacts = listOf(
            MavenArtifact(groupId = "com.jetbrains", "mps", "2024.1.1"),
        ),
    )

    override suspend fun getMavenConnectorConfig(call: TypedApplicationCall<MavenConnectorConfig>) {
        call.respondTyped(data)
    }

    fun install(route: Route) {
        route.connectivityMavenRoutes(this)
        route.connectivityMavenRepositoriesRoutes(this)
        route.connectivityMavenArtifactsRoutes(this)
    }

    override suspend fun getMavenRepository(
        repositoryId: String,
        call: TypedApplicationCall<MavenRepository>,
    ) {
        val repository = data.repositories?.find { it.id == repositoryId }
        if (repository == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respondTyped(repository)
        }
    }

    override suspend fun listMavenRepositories(call: TypedApplicationCall<MavenRepositoryList>) {
        call.respondTyped(MavenRepositoryList(data.repositories))
    }

    override suspend fun listMavenArtifacts(call: TypedApplicationCall<MavenArtifactList>) {
        call.respondTyped(MavenArtifactList(data.artifacts))
    }
}
