package org.modelix.workspace.manager

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.modelix.services.maven_connector.stubs.controllers.ModelixMavenConnectorArtifactsController
import org.modelix.services.maven_connector.stubs.controllers.ModelixMavenConnectorArtifactsController.Companion.modelixMavenConnectorArtifactsRoutes
import org.modelix.services.maven_connector.stubs.controllers.ModelixMavenConnectorController
import org.modelix.services.maven_connector.stubs.controllers.ModelixMavenConnectorController.Companion.modelixMavenConnectorRoutes
import org.modelix.services.maven_connector.stubs.controllers.ModelixMavenConnectorRepositoriesController
import org.modelix.services.maven_connector.stubs.controllers.ModelixMavenConnectorRepositoriesController.Companion.modelixMavenConnectorRepositoriesRoutes
import org.modelix.services.maven_connector.stubs.controllers.TypedApplicationCall
import org.modelix.services.maven_connector.stubs.models.MavenArtifact
import org.modelix.services.maven_connector.stubs.models.MavenArtifactList
import org.modelix.services.maven_connector.stubs.models.MavenConnectorConfig
import org.modelix.services.maven_connector.stubs.models.MavenRepository
import org.modelix.services.maven_connector.stubs.models.MavenRepositoryList

class MavenControllerImpl :
    ModelixMavenConnectorController,
    ModelixMavenConnectorArtifactsController,
    ModelixMavenConnectorRepositoriesController {
    var data = MavenConnectorConfig(
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
        route.modelixMavenConnectorRoutes(this)
        route.modelixMavenConnectorRepositoriesRoutes(this)
        route.modelixMavenConnectorArtifactsRoutes(this)
    }

    override suspend fun getMavenRepository(
        repositoryId: String,
        call: TypedApplicationCall<MavenRepository>,
    ) {
        val repository = data.repositories.find { it.id == repositoryId }
        if (repository == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respondTyped(repository)
        }
    }

    override suspend fun updateMavenRepository(
        repositoryId: String,
        mavenRepository: MavenRepository,
        call: ApplicationCall,
    ) {
        data = data.copy(
            repositories = data.repositories.associateBy { it.id }
                .plus(repositoryId to mavenRepository.copy(id = repositoryId))
                .values.toList(),
        )
        call.respond(HttpStatusCode.OK)
    }

    override suspend fun deleteMavenRepository(
        repositoryId: String,
        call: ApplicationCall,
    ) {
        data = data.copy(
            repositories = data.repositories.filter { it.id != repositoryId },
        )
        call.respond(HttpStatusCode.OK)
    }

    override suspend fun listMavenRepositories(call: TypedApplicationCall<MavenRepositoryList>) {
        call.respondTyped(MavenRepositoryList(data.repositories))
    }

    override suspend fun listMavenArtifacts(call: TypedApplicationCall<MavenArtifactList>) {
        call.respondTyped(MavenArtifactList(data.artifacts))
    }

    override suspend fun deleteMavenArtifact(
        groupId: String,
        artifactId: String,
        call: ApplicationCall,
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun getMavenArtifact(
        groupId: String,
        artifactId: String,
        call: TypedApplicationCall<MavenArtifact>,
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun updateMavenArtifact(
        groupId: String,
        artifactId: String,
        mavenArtifact: MavenArtifact,
        call: ApplicationCall,
    ) {
        TODO("Not yet implemented")
    }
}
