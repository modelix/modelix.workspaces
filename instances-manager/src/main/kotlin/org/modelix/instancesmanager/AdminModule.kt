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
package org.modelix.instancesmanager

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.resources
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.kubernetes.client.openapi.models.CoreV1Event
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.br
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.img
import kotlinx.html.link
import kotlinx.html.postForm
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.submitInput
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import org.json.JSONArray
import org.modelix.workspaces.WorkspaceHash
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

fun Application.adminModule() {
    install(Routing)
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Post)
    }

    routing {
        get("/") {
            call.respondHtml(HttpStatusCode.OK) {
                head {
                    title("Manage Workspace Instances")
                    link("../public/modelix-base.css", rel = "stylesheet")
                    style {
                        unsafe {
                            +"""
                            tbody tr {
                                border: 1px solid #dddddd;
                            }
                            tbody tr:nth-of-type(even) {
                                 background: none;
                            }
                            """.trimIndent()
                        }
                    }
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
                        h1 { +"Workspace Instances" }
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
                                    th {
                                        +"Max. Unassigned"
                                        br {}
                                        +"Instances"
                                    }
                                    th { +"Instance ID" }
                                    th { +"User" }
                                }
                            }
                            for (assignment in DeploymentManager.INSTANCE.getAssignments().sortedBy { it.workspace.hash().hash }.sortedBy { it.workspace.id }) {
                                val assignmentCells: TR.() -> Unit = {
                                    td {
                                        rowSpan = assignment.instances.size.coerceAtLeast(1).toString()
                                        if (!assignment.isLatest) style = "color: #aaa"
                                        span {
                                            style = "font-weight: bold;"
                                            +(assignment.workspace.name ?: "<no name>")
                                        }
                                        br {}
                                        span {
                                            +assignment.workspace.id
                                        }
                                        br {}
                                        span {
                                            style = "color: #888;"
                                            +assignment.workspace.hash().hash
                                        }
                                    }
                                    td {
                                        rowSpan = assignment.instances.size.coerceAtLeast(1).toString()
                                        postForm("change-unassigned") {
                                            hiddenInput {
                                                name = "workspaceHash"
                                                value = assignment.workspace.hash().hash
                                            }
                                            for (newValue in 0..5) {
                                                submitInput(classes = "btn") {
                                                    style = "margin: 1px; padding: 4px 8px;"
                                                    disabled = newValue == assignment.unassignedInstances
                                                    name = "numberOfUnassigned"
                                                    value = "$newValue"
                                                }
                                            }
                                        }
                                    }
                                }

                                if (assignment.instances.isEmpty()) {
                                    tr {
                                        assignmentCells()
                                    }
                                } else {
                                    for (instanceAndIndex in assignment.instances.withIndex()) {
                                        val instance = instanceAndIndex.value
                                        tr {
                                            if (instanceAndIndex.index == 0) assignmentCells()
                                            td {
                                                if (instance.disabled) style = "color: #aaa"
                                                a("log/${instance.id}/", "_blank") {
                                                    +instance.id.name
                                                }
                                            }
                                            td {
                                                if (instance.disabled) style = "color: #aaa"
                                                +when (val owner = instance.owner) {
                                                    is SharedInstanceOwner -> "[${owner.name}]"
                                                    is UnassignedInstanceOwner -> "<unassigned>"
                                                    is UserInstanceOwner -> owner.userId
                                                }
                                            }
                                            td {
                                                if (instance.disabled) {
                                                    postForm("enable-instance") {
                                                        hiddenInput {
                                                            name = "instanceId"
                                                            value = instance.id.name
                                                        }
                                                        submitInput(classes = "btn") {
                                                            value = "Enable"
                                                        }
                                                    }
                                                } else {
                                                    postForm("disable-instance") {
                                                        hiddenInput {
                                                            name = "instanceId"
                                                            value = instance.id.name
                                                        }
                                                        submitInput(classes = "btn") {
                                                            value = "Disable"
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
            }
        }

        post("disable-instance") {
            val instanceId = InstanceName(call.receiveParameters()["instanceId"]!!)
            DeploymentManager.INSTANCE.disableInstance(instanceId)
            call.respondRedirect(".")
        }

        post("enable-instance") {
            val instanceId = InstanceName(call.receiveParameters()["instanceId"]!!)
            DeploymentManager.INSTANCE.enableInstance(instanceId)
            call.respondRedirect(".")
        }

        post("change-unassigned") {
            val parameters = call.receiveParameters()
            val workspaceHash = parameters["workspaceHash"]!!
            val numberOfUnassigned = parameters["numberOfUnassigned"]!!
            DeploymentManager.INSTANCE.changeNumberOfAssigned(WorkspaceHash(workspaceHash), numberOfUnassigned.toInt().coerceIn(0..100))
            call.respondRedirect(".")
        }

        get("log/{instanceId}/content") {
            val instanceId = InstanceName(call.parameters["instanceId"]!!)
            val log = DeploymentManager.INSTANCE.getPodLogs(instanceId) ?: "Instance $instanceId not found"
            call.respondText(log, ContentType.Text.Plain, HttpStatusCode.OK)
        }

        get("log/{instanceId}/events") {
            val instanceId = call.parameters["instanceId"]!!
            val events = DeploymentManager.INSTANCE.getEvents(instanceId)
            val eventTime: (CoreV1Event) -> OffsetDateTime? = {
                listOfNotNull(
                    it.eventTime,
                    it.lastTimestamp,
                    it.firstTimestamp,
                ).firstOrNull()
            }

            val json = JSONArray()
            for (event in events) {
                val row = JSONArray()
                // Format time as HH:mm:ss
                row.put(eventTime(event)?.format(DateTimeFormatter.ISO_LOCAL_TIME) ?: "---")
                row.put(event.type)
                row.put(event.reason)
                row.put(event.message)
                json.put(row)
            }
            call.respondText(json.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("log/{instanceId}/") {
            val resourceName = "/static/log/xxx/log.html"
            val resource = this.javaClass.getResource(resourceName)
            if (resource == null) {
                call.respondText("$resourceName not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(resource.readText(), ContentType.Text.Html)
        }

        resources("/static")
    }
}
