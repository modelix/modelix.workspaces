package org.modelix.workspace.manager

import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.br
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.img
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.postForm
import kotlinx.html.style
import kotlinx.html.submitInput
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import org.modelix.authorization.checkPermission
import org.modelix.authorization.hasPermission
import org.modelix.workspaces.WorkspaceAndHash
import org.modelix.workspaces.WorkspaceHash
import org.modelix.workspaces.WorkspacesPermissionSchema
import org.modelix.workspaces.withHash

class WorkspaceJobQueueUI(val manager: WorkspaceManager) {

    fun install(route: Route) {
        with(route) {
            installRoutes()
        }
    }

    private fun Route.installRoutes() {
        get("/") {
            call.respondHtmlSafe {
                head {
                    title("Workspaces Build Queue")
                    link("../../public/modelix-base.css", rel="stylesheet")
                    style { unsafe {
                        +"""
                            tbody tr {
                                border: 1px solid #dddddd;
                            }
                            tbody tr:nth-of-type(even) {
                                 background: none;
                            }
                        """.trimIndent()
                    }}
                    meta {
                        httpEquiv = "refresh"
                        content = "3"
                    }
                }

                body {
                    style = "display: flex; flex-direction: column; align-items: center;"
                    div {
                        style = "display: flex; justify-content: center;"
                        a("../../") {
                            style = "background-color: #343434; border-radius: 15px; padding: 10px;"
                            img("Modelix Logo") {
                                src = "../../public/logo-dark.svg"
                                width = "70px"
                                height = "70px"
                            }
                        }
                    }
                    div {
                        style = "display: flex; flex-direction: column; justify-content: center;"
                        h1 { +"Workspaces Build Queue" }
                        table {
                            thead {
                                tr {
                                    th {
                                        +"Workspace Name"
                                        br { }
                                        +"Workspace ID"
                                        br { }
                                        +"Workspace Hash"
                                    }
                                    th { +"Status" }
                                    th { }
                                    th { }
                                }
                            }

                            val jobsByHash: Map<WorkspaceAndHash, WorkspaceJobQueue.Job> = manager.buildJobs.getJobs().associateBy { it.workspace }
                            val latestWorkspaces = manager.getAllWorkspaces().sortedBy { it.id }.map { it.withHash() }.toSet()
                            val allWorkspaceHashes: Set<WorkspaceAndHash> = (latestWorkspaces + jobsByHash.keys).toSet()

                            for (workspaceAndHash in allWorkspaceHashes.sortedBy { it.id }) {
                                if (!call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceAndHash.id).buildJob.view)) continue

                                val job = jobsByHash[workspaceAndHash]

                                tr {
                                    td {
                                        if (!latestWorkspaces.contains(workspaceAndHash)) style = "color: #aaa"
                                        +workspaceAndHash.name.orEmpty()
                                        br { }
                                        +workspaceAndHash.id
                                        br { }
                                        +workspaceAndHash.hash().toString()
                                    }
                                    td {
                                        +job?.status?.toString().orEmpty()
                                    }
                                    td {
                                        a("../${workspaceAndHash.hash().hash}/buildlog", target = "_blank") {
                                            +"Show Log"
                                        }
                                    }
                                    td {
                                        if (call.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceAndHash.id).buildJob.restart)) {
                                            postForm("rebuild") {
                                                hiddenInput {
                                                    name = "workspaceHash"
                                                    value = workspaceAndHash.hash().toString()
                                                }
                                                submitInput(classes = "btn") {
                                                    value = "Rebuild"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post("rebuild") {
            val hash = WorkspaceHash(requireNotNull(call.receiveParameters()["workspaceHash"]) {
                "Parameter 'workspaceHash' missing"
            })
            val workspace = requireNotNull(manager.getWorkspaceForHash(hash)) {
                "Workspace with hash '$hash' unknown"
            }
            call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspace.id).buildJob.restart)
            manager.rebuild(hash)
            call.respondRedirect(url = ".")
        }
    }
}