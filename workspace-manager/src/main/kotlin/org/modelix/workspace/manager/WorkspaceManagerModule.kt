/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.modelix.workspace.manager

import com.charleskorn.kaml.Yaml
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.html.DIV
import kotlinx.html.FormEncType
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.body
import kotlinx.html.br
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.html
import kotlinx.html.i
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.submitInput
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.io.FileUtils
import org.modelix.authorization.ModelixAuthorization
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.authorization.checkPermission
import org.modelix.authorization.getUserName
import org.modelix.authorization.hasPermission
import org.modelix.authorization.jwt
import org.modelix.authorization.permissions.PermissionSchemaBase
import org.modelix.authorization.requiresLogin
import org.modelix.gitui.GIT_REPO_DIR_ATTRIBUTE_KEY
import org.modelix.gitui.MPS_INSTANCE_URL_ATTRIBUTE_KEY
import org.modelix.gitui.gitui
import org.modelix.model.server.ModelServerPermissionSchema
import org.modelix.workspaces.SharedInstance
import org.modelix.workspaces.UploadId
import org.modelix.workspaces.Workspace
import org.modelix.workspaces.WorkspaceAndHash
import org.modelix.workspaces.WorkspaceBuildStatus
import org.modelix.workspaces.WorkspaceHash
import org.modelix.workspaces.WorkspacesPermissionSchema
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream

fun Application.workspaceManagerModule() {


    val credentialsEncryption = createCredentialEncryption()
    val manager = WorkspaceManager(credentialsEncryption)
    val maxBodySize = environment.config.property("modelix.maxBodySize").getString()

    install(Routing)
    install(ModelixAuthorization) {
        permissionSchema = WorkspacesPermissionSchema.SCHEMA
        accessControlPersistence = manager.accessControlPersistence
        installStatusPages = true
    }
    install(ContentNegotiation) {
        json()
    }

    routing {
        staticResources("client/", basePackage = "org.modelix.workspace.client")
        staticResources("legacySyncPlugin/", basePackage = "org.modelix.workspace.legacySyncPlugin")

        requiresLogin {
            get("/") {
                call.respondHtmlSafe(HttpStatusCode.OK) {
                    head {
                        title("Workspaces")
                        link("../public/modelix-base.css", rel="stylesheet")
                        style {unsafe {
                            +"""
                            form {
                                margin: auto;
                            }
                            .workspace-name {
                                font-weight: bold;
                                color: #000000;
                            }
                        """.trimIndent()
                        }}
                    }
                    body {
                        style = "display: flex; flex-direction: column; align-items: center;"
                        div {
                            style = "display: flex; justify-content: center;"
                            a("../") {
                                style = "background-color: #343434; border-radius: 15px; padding: 10px;"
                                img("Modelix Logo") {
                                    src = "../public/logo-dark.svg"
                                    width = "70px"
                                    height = "70px"
                                }
                            }
                        }
                        div {
                            style = "display: flex; flex-direction: column; justify-content: center;"
                            h1 { text("Workspaces") }
                            p {
                                +"A workspace allows to deploy an MPS project and all of its dependencies to Modelix and edit it in the browser."
                                br {}
                                +" Solutions are synchronized with the model server and between all MPS instances."
                            }
                            table {
                                thead {
                                    tr {
                                        th { +"Workspace"}
                                        th {
                                            colSpan="5"
                                            +"Actions"
                                        }
                                    }
                                }
                                manager.getWorkspaceIds()
                                    .filter {
                                        call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(it).list)
                                    }
                                    .mapNotNull { manager.getWorkspaceForId(it) }.forEach { workspaceAndHash ->
                                        val workspace = workspaceAndHash.workspace
                                        val workspaceId = workspace.id
                                        val canRead = call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.read)
                                        val canWrite = call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.write)
                                        val canDelete = call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.delete)
                                        tr {
                                            td {
                                                a(classes = "workspace-name") {
                                                    if (canRead) href = "$workspaceId/edit"
                                                    text((workspace?.name ?: "<no name>") + " ($workspaceId)")
                                                }
                                            }
                                            // Shadow models based UI was removed
//                                            td {
//                                                if (canRead) {
//                                                    a {
//                                                        href = "../${workspaceInstanceUrl(workspaceAndHash)}/project"
//                                                        text("Open Web Interface")
//                                                    }
//                                                }
//                                            }
                                            td {
                                                if (canRead) {
                                                    a {
                                                        href = "../${workspaceInstanceUrl(workspaceAndHash)}/ide/"
                                                        text("Open MPS")
                                                    }
                                                }
                                                for (sharedInstance in workspace.sharedInstances) {
                                                    if (sharedInstance.allowWrite && !canWrite) continue
                                                    br {}
                                                    a {
                                                        href = "../${workspaceInstanceUrl(workspaceAndHash, sharedInstance)}/ide/"
                                                        text("Open MPS [${sharedInstance.name}]")
                                                    }
                                                }
                                            }
                                            td {
                                                if (canRead) {
                                                    a {
                                                        href = "../${workspaceInstanceUrl(workspaceAndHash)}/generator/"
                                                        text("Generator")
                                                    }
                                                }
                                                for (sharedInstance in workspace.sharedInstances) {
                                                    if (sharedInstance.allowWrite && !canWrite) continue
                                                    br {}
                                                    a {
                                                        href = "../${workspaceInstanceUrl(workspaceAndHash, sharedInstance)}/generator/"
                                                        text("Generator [${sharedInstance.name}]")
                                                    }
                                                }
                                            }
                                            td {
                                                if (canRead) {
                                                    workspace.gitRepositories.forEachIndexed { index, gitRepository ->
                                                        a {
                                                            href = "$workspaceId/git/$index/"
                                                            val suffix = if (gitRepository.name.isNullOrEmpty()) "" else " (${gitRepository.name})"
                                                            text("Git History$suffix")
                                                        }
                                                    }
                                                    workspace.uploadIds().associateWith { findGitRepo(manager.getUploadFolder(it)) }
                                                        .filter { it.value != null }.forEach { upload ->
                                                            a {
                                                                href = "$workspaceId/git/u${upload.key}/"
                                                                text("Git History")
                                                            }
                                                        }
                                                }
                                            }
                                            td {
                                                if (canRead) {
                                                    a {
                                                        href = "$workspaceId/model-history"
                                                        text("Model History")
                                                    }
                                                }
                                            }
                                            td {
                                                if (canDelete) {
                                                    postForm("./remove-workspace") {
                                                        style = "display: inline-block"
                                                        hiddenInput {
                                                            name = "workspaceId"
                                                            value = workspaceId
                                                        }
                                                        submitInput(classes = "btn") {
                                                            value = "Remove"
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                if (call.hasPermission(WorkspacesPermissionSchema.workspaces.add)) {
                                    tr {
                                        td {
                                            colSpan = "6"
                                            form {
                                                action = "new"
                                                method = FormMethod.post
                                                input (classes = "btn") {
                                                    type = InputType.submit
                                                    value = "+ New Workspace"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        br {}
                        div {
                            a(href = "permissions/manage") {
                                +"Manage Permissions"
                            }
                        }
                    }
                }
            }

            get("{workspaceId}/hash") {
                val workspaceId = call.parameters["workspaceId"]!!
                call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).list)
                val workspaceAndHash = manager.getWorkspaceForId(workspaceId)
                if (workspaceAndHash == null) {
                    call.respond(HttpStatusCode.NotFound, "Workspace $workspaceId not found")
                } else {
                    call.respondText(workspaceAndHash.hash().hash, ContentType.Text.Plain, HttpStatusCode.OK)
                }
            }

            route("{workspaceId}/git/{repoOrUploadIndex}/") {
                intercept(ApplicationCallPipeline.Call) {
                    val workspaceId = call.parameters["workspaceId"]!!
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.read)
                    val repoOrUploadIndex = call.parameters["repoOrUploadIndex"]!!
                    var repoIndex: Int? = null
                    var uploadId: UploadId? = null
                    if (repoOrUploadIndex.startsWith("u")) {
                        uploadId = UploadId(repoOrUploadIndex.drop(1))
                    } else {
                        repoIndex = repoOrUploadIndex.toInt()
                    }
                    val workspaceAndHash = manager.getWorkspaceForId(workspaceId)
                    if (workspaceAndHash == null) {
                        call.respondText("Workspace $workspaceId not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                        return@intercept
                    }
                    val workspace = workspaceAndHash.workspace
                    val repoDir: File
                    if (repoIndex != null) {
                        val repos = workspace.gitRepositories
                        if (!repos.indices.contains(repoIndex)) {
                            call.respondText("Git repository with index $repoIndex doesn't exist", ContentType.Text.Plain, HttpStatusCode.NotFound)
                            return@intercept
                        }
                        val repo = repos[repoIndex]
                        val gitRepoWitDecryptedCredentials = credentialsEncryption.copyWithDecryptedCredentials(repo)
                        val repoManager = GitRepositoryManager(gitRepoWitDecryptedCredentials, manager.getWorkspaceDirectory(workspace))
                        if (!repoManager.repoDirectory.exists()) {
                            repoManager.updateRepo()
                        }
                        repoDir = repoManager.repoDirectory
                    } else {
                        val uploadFolder = manager.getUploadFolder(uploadId!!)
                        if (!uploadFolder.exists()) {
                            call.respondText("Upload $uploadId doesn't exist", ContentType.Text.Plain, HttpStatusCode.NotFound)
                            return@intercept
                        }
                        if (uploadFolder.resolve(".git").exists()) {
                            repoDir = uploadFolder
                        } else {
                            val repoDirFromUpload = findGitRepo(uploadFolder)
                            if (repoDirFromUpload == null) {
                                call.respondText("No git repository found in upload $uploadId", ContentType.Text.Plain, HttpStatusCode.NotFound)
                                return@intercept
                            }
                            repoDir = repoDirFromUpload
                        }
                    }
                    call.attributes.put(GIT_REPO_DIR_ATTRIBUTE_KEY, repoDir)
                    call.attributes.put(MPS_INSTANCE_URL_ATTRIBUTE_KEY, "../../../../${workspaceInstanceUrl(workspaceAndHash)}/")
                }
                gitui()
            }

            post("new") {
                call.checkPermission(WorkspacesPermissionSchema.workspaces.add)
                val jwt = call.jwt()!!
                val workspace = manager.newWorkspace(ModelixJWTUtil().extractUserId(jwt))
                call.respondRedirect("${workspace.id}/edit")
            }

            route("uploads") {
                get("{uploadId}") {
                    val uploadId = UploadId(call.parameters["uploadId"]!!)
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.uploads.upload(uploadId.id).read)
                    val folder = manager.getUploadFolder(uploadId)
                    call.respondOutputStream(ContentType.Application.Zip) {
                        ZipOutputStream(this).use { zip ->
                            zip.copyFiles(folder, mapPath = { folder.toPath().parent.relativize(it)}, extractZipFiles = true)
                        }
                    }
                }
            }

            route("{workspaceId}") {
                fun PipelineContext<*, ApplicationCall>.workspaceId() = call.parameters["workspaceId"]!!

                get("edit") {
                    val id = workspaceId()
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(id).config.read)
                    val workspaceAndHash = manager.getWorkspaceForId(id)
                    if (workspaceAndHash == null) {
                        call.respond(HttpStatusCode.NotFound, "Workspace $id not found")
                        return@get
                    }
                    val workspace = workspaceAndHash.workspace
                    val yaml = Yaml.default.encodeToString(workspace)
                    val canWrite = call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.write)

                    this.call.respondHtml(HttpStatusCode.OK) {
                        head {
                            title { text("Edit Workspace") }
                            link("../../public/modelix-base.css", rel="stylesheet")
                            link("../../public/menu-bar.css", rel="stylesheet")
                        }
                        body {
                            div("menu") {
                                a("../../") {
                                    style = "height: 70px;"
                                    img("Modelix Logo") {
                                        src = "../../public/logo-dark.svg"
                                        width = "70px"
                                        height = "70px"
                                    }
                                }
                                div("menuItem") {
                                    a("../") { +"Workspace List" }
                                }
                                div("menuItem") {
                                    a("../${workspaceAndHash.hash().hash}/buildlog") { +"Build Log" }
                                }
                                // Shadow models based UI was removed
//                                div("menuItem") {
//                                    a("../../${workspaceInstanceUrl(workspaceAndHash)}/project") { +"Open Web Interface" }
//                                }
                                div("menuItem") {
                                    a("../../${workspaceInstanceUrl(workspaceAndHash)}/ide/") { +"Open MPS" }
                                }
                                div("menuItem") {
                                    a("../../${workspaceInstanceUrl(workspaceAndHash)}/generator/") { +"Generator" }
                                }
                                workspace.gitRepositories.forEachIndexed { index, gitRepository ->
                                    div("menuItem") {
                                        a("git/$index/") {
                                            val suffix = if (gitRepository.name.isNullOrEmpty()) "" else " (${gitRepository.name})"
                                            text("Git History$suffix")
                                        }
                                    }
                                }
                                workspace.uploadIds().associateWith { findGitRepo(manager.getUploadFolder(it)) }
                                    .filter { it.value != null }.forEach { upload ->
                                        div("menuItem") {
                                            a("git/u${upload.key}/") {
                                                text("Git History")
                                            }
                                        }
                                    }
                            }
                            br()
                            div {
                                style = "display: flex"
                                div {
                                    h1 { +"Edit Workspace"}
                                    form {
                                        action = "./update"
                                        method = FormMethod.post
                                        textArea {
                                            name = "content"
                                            style = "width: 800px; height: 500px; border-radius: 4px; padding: 12px;"
                                            text(yaml)
                                        }
                                        if (canWrite) {
                                            br()
                                            input(classes = "btn") {
                                                type = InputType.submit
                                                value = "Save Changes"
                                            }
                                        }
                                    }
                                }
                                div {
                                    style = "display: inline-block; margin-top: 15px; padding: 0px 12px;"
                                    h2 {
                                        style = "margin-bottom: 10px;"
                                        +"Explanation"
                                    }
                                    ul {
                                        style = "margin-top: 0;"
                                        li {
                                            b { +"name" }
                                            +": Is just shown to the user in the workspace list."
                                        }
                                        li {
                                            b { +"mpsVersion" }
                                            +": This is experimental."
                                            +" The workspace will be executed using a docker image from a Modelix release for a different MPS version."
                                        }
                                        li {
                                            b { +"modelRepositories" }
                                            +": Currently not used. A separate repository on the model server is created for each workspace."
                                            +" The ID of the repository for this workspace is "
                                            i { +"workspace_${workspace.id}" }
                                            +"."
                                        }
                                        li {
                                            b { +"gitRepositories" }
                                            +": Git repository containing an MPS project. No build script is required."
                                            +" Modelix will build all languages including their dependencies after cloning the repository."
                                            +" If this repository is not public, credentials can be specified."
                                            +" Alternatively, a project can be uploaded as a .zip file. (see below)"
                                            ul {
                                                li {
                                                    b { +"url" }
                                                    +": Address of the Git repository."
                                                }
                                                li {
                                                    b { +"name" }
                                                    +": Currently not used."
                                                }
                                                li {
                                                    b { +"branch" }
                                                    +": If no commitHash is specified, the latest commit from this branch is used."
                                                }
                                                li {
                                                    b { +"commitHash" }
                                                    +": A Git commit hash can be specified to ensure that always the same version is used."
                                                }
                                                li {
                                                    b { +"paths" }
                                                    +": If this repository contains additional modules that you don't want to use in Modelix,"
                                                    +" you can specify a list of folders that you want to include."
                                                }
                                                li {
                                                    b { +"credentials" }
                                                    +": The credentials are encrypted before they are stored."
                                                    ul {
                                                        li { b { +"user" } }
                                                        li { b { +"password" } }
                                                    }
                                                }
                                            }
                                        }
                                        li {
                                            b { +"mavenRepositories" }
                                            +": Some artifacts are bundled with Modelix."
                                            +" If you additional ones, here you can specify maven repositories that contain them."
                                            ul {
                                                li {
                                                    b { +"url" }
                                                    +": You probably want to use this one: "
                                                    i { +"https://artifacts.itemis.cloud/repository/maven-mps/" }
                                                }
                                            }
                                        }
                                        li {
                                            b { +"mavenDependencies" }
                                            +": Maven coordinates to a .zip file containing MPS modules/plugins."
                                            +" Example: "
                                            i { +"de.itemis.mps:extensions:2020.3.2179.1ee9c94:zip" }
                                        }
                                        li {
                                            b { +"uploads" }
                                            +": There is a special section for managing uploads. Directly editing this list is not required."
                                        }
                                        li {
                                            b { +"ignoredModules" }
                                            +": A list of MPS module IDs that should be excluding from generation."
                                            +" Also missing dependencies that should be ignored can be listed here."
                                            +" This section is usually used when the generation fails and editing the project is not possible."
                                        }
                                    }
                                }
                            }
                            br()
                            div {
                                style = "padding: 3px;"
                                b { +"Uploads:" }
                                val allUploads = manager.getExistingUploads().associateBy { it.name }
                                val uploadContent: (Map.Entry<String, File?>)->String = { uploads ->
                                    val fileNames: List<File> = (uploads.value?.listFiles()?.toList() ?: listOf())
                                    fileNames.joinToString(", ") { it.name }
                                }
                                table {
                                    for (upload in allUploads.toSortedMap()) {
                                        val uploadResource = WorkspacesPermissionSchema.workspaces.uploads.upload(upload.key)
                                        tr {
                                            td { +upload.key }
                                            td { +uploadContent(upload) }
                                            td {
                                                if (canWrite) {
                                                    if (workspace.uploads.contains(upload.key)) {
                                                        form {
                                                            action = "./remove-upload"
                                                            method = FormMethod.post
                                                            input {
                                                                type = InputType.hidden
                                                                name = "uploadId"
                                                                value = upload.key
                                                            }
                                                            input {
                                                                type = InputType.submit
                                                                value = "Remove"
                                                            }
                                                        }
                                                    } else {
                                                        form {
                                                            action = "./use-upload"
                                                            method = FormMethod.post
                                                            input {
                                                                type = InputType.hidden
                                                                name = "uploadId"
                                                                value = upload.key
                                                            }
                                                            input {
                                                                type = InputType.submit
                                                                value = "Add"
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            td {
                                                if (call.hasPermission(uploadResource.delete)) {
                                                    form {
                                                        action = "./delete-upload"
                                                        method = FormMethod.post
                                                        hiddenInput {
                                                            name = "uploadId"
                                                            value = upload.key
                                                        }
                                                        submitInput (classes = "btn"){
                                                            style = "background-color: red"
                                                            value = "Delete"
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (canWrite) {
                                    br()
                                    br()
                                    b { +"Upload new file or directory (max $maxBodySize MiB):" }
                                    form {
                                        action = "./upload"
                                        method = FormMethod.post
                                        encType = FormEncType.multipartFormData
                                        div {
                                            span {
                                                style = "display: inline-block; width: 140px;"
                                                +"Choose File(s): "
                                            }
                                            input {
                                                type = InputType.file
                                                name = "file"
                                                multiple = true
                                            }
                                        }
                                        div {
                                            span {
                                                style = "display: inline-block; width: 147px;"
                                                +"Choose Directory: "
                                            }
                                            input {
                                                type = InputType.file
                                                name = "folder"
                                                attributes["webkitdirectory"] = "true"
                                                attributes["mozdirectory"] = "true"
                                            }
                                        }
                                        div {
                                            input (classes = "btn") {
                                                type = InputType.submit
                                                value = "Upload"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                post("update") {
                    val yamlText = call.receiveParameters()["content"]
                    val id = workspaceId()
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(id).config.write)
                    if (yamlText == null) {
                        call.respond(HttpStatusCode.BadRequest, "Content missing")
                        return@post
                    }
                    val workspace: Workspace
                    try {
                        workspace = Yaml.default.decodeFromString<Workspace>(yamlText)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, e.message ?: "Parse error")
                        return@post
                    }
                    // set ID just in case the user copy-pastes a workspace and forgets to change the ID
                    manager.update(workspace.copy(id = id))
                    call.respondRedirect("./edit")
                }

                post("add-maven-dependency") {
                    val id = workspaceId()
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(id).config.write)
                    val workspaceAndHash = manager.getWorkspaceForId(id)
                    if (workspaceAndHash == null) {
                        call.respond(HttpStatusCode.NotFound, "Workspace $id not found")
                        return@post
                    }
                    val workspace = workspaceAndHash.workspace
                    val coordinates = call.receiveParameters()["coordinates"]
                    if (coordinates.isNullOrEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, "coordinates missing")
                    } else {
                        manager.update(workspace.copy(mavenDependencies = workspace.mavenDependencies + coordinates))
                        call.respondRedirect("./edit")
                    }
                }

                post("upload") {
                    val workspaceId = workspaceId()
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.uploads.add)
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.write)
                    val workspace = manager.getWorkspaceForId(workspaceId)?.workspace
                    if (workspace == null) {
                        call.respondText("Workspace $workspaceId not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                        return@post
                    }

                    val outputFolder = manager.newUploadFolder()

                    call.receiveMultipart().forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val name = part.originalFileName
                            if (!name.isNullOrEmpty()) {
                                val outputFile = File(outputFolder, name)
                                part.streamProvider().use {
                                    FileUtils.copyToFile(it, outputFile)
                                }
                                if (outputFile.extension.lowercase() == "zip") {
                                    ZipUtil.explode(outputFile)
                                }
                            }
                        }
                        part.dispose()
                    }

                    manager.update(workspace.copy(uploads = workspace.uploads + outputFolder.name))

                    call.respondRedirect("./edit")
                }

                post("use-upload") {
                    val workspaceId = workspaceId()
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.write)
                    val uploadId = call.receiveParameters()["uploadId"]!!
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.uploads.upload(uploadId).read)
                    val workspace = manager.getWorkspaceForId(workspaceId)?.workspace!!
                    manager.update(workspace.copy(uploads = workspace.uploads + uploadId))
                    call.respondRedirect("./edit")
                }

                post("remove-upload") {
                    val workspaceId = workspaceId()
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.write)
                    val uploadId = call.receiveParameters()["uploadId"]!!
                    val workspace = manager.getWorkspaceForId(workspaceId)?.workspace!!
                    manager.update(workspace.copy(uploads = workspace.uploads - uploadId))
                    call.respondRedirect("./edit")
                }

                post("delete-upload") {
                    val uploadId = UploadId(call.receiveParameters()["uploadId"]!!)
                    call.checkPermission(WorkspacesPermissionSchema.workspaces.uploads.upload(uploadId.id).delete)
                    val allWorkspaces = manager.getWorkspaceIds().mapNotNull { manager.getWorkspaceForId(it)?.workspace }
                    for (workspace in allWorkspaces.filter { it.uploadIds().contains(uploadId) }) {
                        manager.update(workspace.copy(uploads = workspace.uploads - uploadId.id))
                    }
                    manager.deleteUpload(uploadId)
                    call.respondRedirect("./edit")
                }

                get("model-history") {
                    // ensure the user has the necessary permission on the model-server
                    val userId = call.getUserName()
                    if (userId != null) {
                        val permissionId = when {
                            call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId()).modelRepository.write) -> ModelServerPermissionSchema.repository("workspace_${workspaceId()}").write
                            call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId()).modelRepository.read) -> ModelServerPermissionSchema.repository("workspace_${workspaceId()}").read
                            else -> null
                        }
                        if (permissionId != null) {
                            HttpClient(CIO).submitForm(
                                url = System.getenv("model_server_url") + "permissions/grant",
                                formParameters = parameters {
                                    append("userId", userId)
                                    append("permissionId", permissionId.fullId)
                                }
                            ) {
                                expectSuccess = true
                                bearerAuth(manager.jwtUtil.createAccessToken("workspace-manager@modelix.org", listOf(
                                    permissionId.fullId // for granting a permission to someone else it's sufficient to have that permission
                                )))
                            }
                        }
                    }

                    call.respondRedirect("../../model/history/workspace_${workspaceId()}/master/")
                }
            }

            route("{workspaceHash}") {
                intercept(ApplicationCallPipeline.Call) {
                    val workspaceHash = WorkspaceHash(call.parameters["workspaceHash"]!!)
                    val workspace = manager.getWorkspaceForHash(workspaceHash)?.workspace
                    if (workspace != null) {
                        call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.read)
                    }
                }

                get {
                    val workspaceHash = WorkspaceHash(call.parameters["workspaceHash"]!!)
                    val workspace = manager.getWorkspaceForHash(workspaceHash)?.workspace
                    if (workspace == null) {
                        call.respond(HttpStatusCode.NotFound, "workspace $workspaceHash not found")
                        return@get
                    }
                    val decryptCredentials = call.request.queryParameters["decryptCredentials"] == "true"
                    val decrypted = if (decryptCredentials) {
                        // TODO check permission to read decrypted credentials
                        credentialsEncryption.copyWithDecryptedCredentials(workspace)
                    } else {
                        workspace
                    }
                    call.respond(decrypted)
                }

                get("git/{repoIndex}/repo.zip") {
                    val workspaceHash = WorkspaceHash(call.parameters["workspaceHash"]!!)
                    val workspace = manager.getWorkspaceForHash(workspaceHash)?.workspace
                    if (workspace == null) {
                        call.respond(HttpStatusCode.NotFound, "workspace $workspaceHash not found")
                        return@get
                    }
                    val repoIndex = call.parameters["repoIndex"]!!.toInt()
                    val gitRepo = workspace.gitRepositories.getOrNull(repoIndex)
                    if (gitRepo == null) {
                        call.respond(HttpStatusCode.NotFound, "workspace $workspaceHash doesn't contain a git repository with index $repoIndex")
                        return@get
                    }

                    val gitRepoWitDecryptedCredentials = credentialsEncryption.copyWithDecryptedCredentials(gitRepo)
                    val gitRepoManager = GitRepositoryManager(gitRepoWitDecryptedCredentials, manager.getWorkspaceDirectory(workspace))
                    gitRepoManager.updateRepo()
                    call.respondOutputStream(ContentType.Application.Zip) {
                        ZipOutputStream(this).use { zip ->
                            gitRepoManager.zip(gitRepo.paths, zip, true)
                        }
                    }
                }

                get("buildlog") {
                    val workspaceHash = WorkspaceHash(call.parameters["workspaceHash"]!!)
                    val job = manager.buildWorkspaceDownloadFileAsync(workspaceHash)
                    val respondStatus: suspend (String, DIV.() -> Unit)->Unit = { refresh, text ->
                        call.respondHtmlSafe {
                            head {
                                meta {
                                    httpEquiv = "refresh"
                                    content = refresh
                                }
                            }
                            body {
                                div {
                                    text()
                                }
                                br {  }
                                br {  }
                                pre {
                                    +job.getLog()
                                }
                            }
                        }
                    }
                    when (job.status) {
                        WorkspaceBuildStatus.New, WorkspaceBuildStatus.Queued -> respondStatus("3") { +"Workspace is queued for building ..." }
                        WorkspaceBuildStatus.Running -> respondStatus("10") { +"Downloading and building modules ..." }
                        WorkspaceBuildStatus.FailedBuild -> respondStatus("10") { +"Failed to build the workspace ..." }
                        WorkspaceBuildStatus.FailedZip -> respondStatus("30") { +"Failed to ZIP the workspace ..." }
                        WorkspaceBuildStatus.AllSuccessful, WorkspaceBuildStatus.ZipSuccessful -> {
                            respondStatus("30") {
                                if (job.status == WorkspaceBuildStatus.ZipSuccessful) {
                                    +"Failed to build the workspace. "
                                }
                                +"Workspace files ready are for download: "
                                val fileName = "workspace.zip"
                                a(href = fileName) {
                                    +fileName
                                }
                            }
                        }
                    }
                }

                get("status") {
                    val workspaceHash = WorkspaceHash(call.parameters["workspaceHash"]!!)
                    val job = manager.buildWorkspaceDownloadFileAsync(workspaceHash)
                    call.respondText(job.status.toString(), ContentType.Text.Plain, HttpStatusCode.OK)
                }

                get("output") {
                    val workspaceHash = WorkspaceHash(call.parameters["workspaceHash"]!!)
                    val job = manager.buildWorkspaceDownloadFileAsync(workspaceHash)
                    call.respondText(job.getLog(), ContentType.Text.Plain, HttpStatusCode.OK)
                }

                put("workspace.zip") {
                    // TODO check permission
                    val workspaceHash = WorkspaceHash(call.parameters["workspaceHash"]!!)
                    val workspace = manager.getWorkspaceForHash(workspaceHash)
                    if (workspace == null) {
                        call.respondText("Workspace $workspaceHash not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                    } else {
                        val file = manager.getDownloadFile(workspaceHash).absoluteFile
                        file.parentFile.mkdirs()
                        FileOutputStream(file).use { out ->
                            call.receiveChannel().copyTo(out)
                        }
                        call.respondText("OK")
                    }
                }

                get("workspace.zip") {
                    val workspaceHash = WorkspaceHash(call.parameters["workspaceHash"]!!)
                    val workspace = manager.getWorkspaceForHash(workspaceHash)
                    if (workspace == null) {
                        call.respondText("Workspace $workspaceHash not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                    } else {
                        val file = manager.getDownloadFile(workspaceHash)
                        if (file.exists()) {
                            call.respondFile(file)
                        } else {
                            call.respondText("""File doesn't exist yet. <a href="queue">Start a build job for the workspace.</a>""", ContentType.Text.Html, HttpStatusCode.NotFound)
                        }
                    }
                }
            }

            post("/remove-workspace") {
                val workspaceId = call.receiveParameters()["workspaceId"]!!
                call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).delete)
                manager.removeWorkspace(workspaceId)
                call.respondRedirect(".")
            }

            route("rest") {
                get("access-control-data") {
                    call.checkPermission(PermissionSchemaBase.permissionData.read)
                    call.respondText(
                        Json.encodeToString(manager.accessControlPersistence.read()),
                        ContentType.Application.Json
                    )
                }
                route("workspaces") {
                    get {
                        val workspaces = manager.getAllWorkspaces().filter {
                            call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(it.id).list)
                        }
                        call.respondText(Json.encodeToString(workspaces), ContentType.Application.Json)
                    }
                    get("ids") {
                        val ids = manager.getWorkspaceIds().filter {
                            call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(it).list)
                        }
                        call.respondText(ids.joinToString("\n"))
                    }
                    route("by-id") {
                        route("{workspaceId}") {
                            get("workspace.json") {
                                val workspaceId = call.parameters["workspaceId"]!!
                                call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.read)
                                val workspace = manager.getWorkspaceForId(workspaceId)?.workspace
                                if (workspace == null) {
                                    call.respond(HttpStatusCode.NotFound, "Workspace not found: $workspaceId")
                                    return@get
                                }
                                call.respondText(Json.encodeToString(workspace), ContentType.Application.Json)
                            }
                        }
                    }
                    route("by-hash") {
                        route("{workspaceHash}") {
                            get("workspace.json") {
                                val workspaceHash = WorkspaceHash(call.parameters["workspaceHash"]!!)
                                val workspace = manager.getWorkspaceForHash(workspaceHash)?.workspace
                                if (workspace == null) {
                                    call.respond(HttpStatusCode.NotFound, "Workspace not found: $workspaceHash")
                                    return@get
                                }
                                call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.read)
                                call.respondText(Json.encodeToString(workspace), ContentType.Application.Json)
                            }
                        }
                    }
                }
            }
        }

        get("/health") {
            call.respondText("healthy", ContentType.Text.Plain, HttpStatusCode.OK)
        }
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Post)
    }
}

private fun createCredentialEncryption(): CredentialsEncryption {
    // Secrets mounted as files are more secure than environment variables
    // because environment variables can more easily leak or be extracted.
    // See https://stackoverflow.com/questions/51365355/kubernetes-secrets-volumes-vs-environment-variables
    val credentialsEncryptionKeyFile = File("/secrets/workspacesecret/workspace-credentials-key.txt")
    val credentialsEncryptionKey = credentialsEncryptionKeyFile.readLines().first()
    return CredentialsEncryption(credentialsEncryptionKey)
}

private fun findGitRepo(folder: File): File? {
    if (!folder.exists()) return null
    if (folder.name == ".git") return folder.parentFile
    if (folder.resolve(".git").exists()) return folder.resolve(".git")
    val children = (folder.listFiles() ?: emptyArray())
    if (children.size == 1) {
        return findGitRepo(children[0])
    }
    return null
}

/**
 * respondHtml fails to respond anything if an exception is thrown in the body and an error handler is installed
 * that tries to respond an error page.
 */
suspend fun ApplicationCall.respondHtmlSafe(status: HttpStatusCode = HttpStatusCode.OK, block: HTML.() -> Unit) {
    val htmlText = createHTML().html {
        block()
    }
    respondText(htmlText, ContentType.Text.Html, status)
}

fun workspaceInstanceUrl(workspace: WorkspaceAndHash) = "workspace-${workspace.id}-${workspace.hash()}/own"
fun workspaceInstanceUrl(workspace: WorkspaceAndHash, instance: SharedInstance) = "workspace-${workspace.id}-${workspace.hash()}/" + instance.name