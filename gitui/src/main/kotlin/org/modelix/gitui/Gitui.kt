/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelix.gitui

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.resources
import io.ktor.server.http.content.static
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.util.pipeline.PipelineContext
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.onClick
import kotlinx.html.radioInput
import kotlinx.html.script
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import java.io.File

class Gitui {

    private val repositories = HashMap<String, GitRepository>()

    fun getRepositories() = repositories.values.toList()

    fun getRepository(id: String) = repositories[id]

    fun registerGitRepository(folder: File) {
        val repo = GitRepository(folder.canonicalFile)
        repositories[repo.id] = repo
    }

    fun unregisterGitRepository(folder: File) {
        repositories.values.filter { it.folder == folder }.forEach { repositories.remove(it.id) }
    }
}
val GIT_REPO_DIR_ATTRIBUTE_KEY = io.ktor.util.AttributeKey<File>("gitRepoDir")
val MPS_INSTANCE_URL_ATTRIBUTE_KEY = io.ktor.util.AttributeKey<String>("mpsInstanceUrl")
fun Route.gitui(): Gitui {
    val gitui = Gitui()

    get {
        val repoDir = call.attributes.getOrNull(GIT_REPO_DIR_ATTRIBUTE_KEY)
        val mpsInstanceUrl = call.attributes.getOrNull(MPS_INSTANCE_URL_ATTRIBUTE_KEY)
        if (repoDir == null) {
            call.respondHtml(HttpStatusCode.OK) {
                head {
                    title("Git Repositories")
                }
                body {
                    h1 { text("Git Repositories") }
                    ul {
                        gitui.getRepositories().forEach { repo ->
                            li {
                                a {
                                    href = repo.id
                                    +repo.name
                                }
                            }
                        }
                    }
                }
            }
        } else {
            repoPage(GitRepository(repoDir), mpsInstanceUrl)
        }
    }

    get("{repositoryId}") {
        val repositoryId = call.parameters["repositoryId"]!!
        val repo = gitui.getRepository(repositoryId)
        if (repo == null) {
            call.respondText(ContentType.Text.Plain, HttpStatusCode.NotFound) { "Repository $repositoryId not found" }
            return@get
        }
        repoPage(repo, null)
    }

    get("/health") {
        call.respondText("healthy", ContentType.Text.Plain, HttpStatusCode.OK)
    }

    static("static") {
        resources("static")
    }

    return gitui
}

private suspend fun PipelineContext<Unit, ApplicationCall>.repoPage(repo: GitRepository, mpsInstanceUrl: String?) {
    call.respondHtml(HttpStatusCode.OK) {
        head {
            title("Git Repository: ${repo.name}")
            script {
                src = "static/gitui.js"
            }
            style {
                unsafe {
                    +"""
                      body {
                          font-family: sans-serif;
                      }
                    """.trimIndent()
                }
            }
        }
        body {
            h1 { +"Branches" }
            div {
                button {
                    id = "showDiffButton"
                    if (mpsInstanceUrl == null) {
                        onClick = """openBranchDiff()"""
                    } else {
                        onClick = """openBranchDiff("$mpsInstanceUrl")"""
                    }
                    +"Show Diff"
                }
            }
            repo.branches().forEach { branch ->
                div {
                    radioInput {
                        name = "left"
                        // onClick = """branchCheckboxClick(this, "${branch.shortName}", "${branch.commitHash}")"""
                        value = branch.commitHash
                        classes = setOf("branchRadio")
                    }
                    radioInput {
                        name = "right"
                        // onClick = """branchCheckboxClick(this, "${branch.shortName}", "${branch.commitHash}")"""
                        value = branch.commitHash
                        classes = setOf("branchRadio")
                    }
                    +branch.shortName
                }
            }
            /*
                                    h1 { +"Commits" }
                                    table {
                                        thead {
                                            tr {
                                                th { +"ID" }
                                                th { +"Author" }
                                                th { +"Committer" }
                                                th { +"Message" }
                                            }
                                        }
                                        tbody {
                                            repo.history().forEach { commit ->
                                                tr {
                                                    td { +commit.id.name.toString() }
                                                    td {
                                                        +commit.authorIdent.name.toString()
                                                        br {  }
                                                        +commit.authorIdent.`when`.toString()
                                                    }
                                                    td {
                                                        +commit.committerIdent.name.toString()
                                                        br {  }
                                                        +commit.committerIdent.`when`.toString()
                                                    }
                                                    td {
                                                        pre {
                                                            +commit.fullMessage.toString()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
             */
        }
    }
}
