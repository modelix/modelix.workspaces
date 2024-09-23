package org.modelix.workspace.manager.gitsync

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import org.modelix.authorization.serviceAccountTokenProvider
import org.modelix.model.client2.ModelClientV2
import org.modelix.model.lazy.BranchReference
import org.modelix.model.lazy.RepositoryId
import org.modelix.workspace.manager.WorkspaceManager
import org.modelix.workspaces.Workspace

suspend fun buildGitSyncPage(
    workspace: Workspace,
    modelClient: ModelClientV2,
    gitSyncJobService: IGitSyncJobService,
): String {
    val branchReference = RepositoryId("workspace_" + workspace.id).getBranchReference("master")
    // TODO MODELIX-597 handle non existence model-server branch (aka. before first sync from MPS to model-server),
    // Show message or something.
    val latestVersion = modelClient.pullHash(branchReference)
    val jobs = gitSyncJobService.getJobs(workspace.id)
    val sortedJobs = jobs.sortedByDescending { it.startTime }
    return createHTML().html {
        head {
            title("Workspace ${workspace.id} | Git Synchronization")
            link("../../../public/modelix-base.css", rel = "stylesheet")
            style {
                unsafe {
                    +"""
                        table form {
                          margin: auto;
                        }
                        
                        form.job-form {
                          display: table;
                        }
    
                        div.job-form-row {
                          display: table-row;
                        }
    
                        label,
                        input {
                          display: table-cell;
                          margin-bottom: 10px;
                        }
    
                        label {
                          padding-right: 10px;
                        }
                    """.trimIndent()
                }
            }
        }
        body {
            h1 {
                +"Synchronization to Git for Workspace ${workspace.id}"
            }
            h2 {
                +"Past Synchronizations"
            }
            table {
                thead {
                    tr {
                        th { +"Job ID" }
                        th { +"Start Time" }
                        th { +"Status" }
                        th { +"Version" }
                        th { +"Repository" }
                        th { +"Source Ref" }
                        th { +"Target Branch" }
                        th {
                            // TODO MODELIX-597 check who has permission for actions
                            // Admins might have actions but users should be able to view status.
                            colSpan = "2"
                            +"Actions"
                        }
                    }
                }
                for (job in sortedJobs) {
                    tr {
                        td { +job.id }
                        td { +job.startTime }
                        td { +job.status.toString() }
                        td { +job.configuration.version }
                        td { +job.configuration.repository }
                        td { +job.configuration.sourceRef }
                        td { +job.configuration.targetBranch }
                        td {
                            a {
                                href = "job/${job.id}/log"
                                +"Logs"
                            }
                        }
                        td {
                            postForm("job/${job.id}/delete") {
                                submitInput(classes = "btn") {
                                    // TODO MODELIX-597 check who has permission for actions
                                    // Admins might have actions but users should be able to view status.
                                    value = "Delete"
                                }
                            }
                        }
                    }
                }
            }
            h2 {
                +"Create Synchronizations"
            }
            form(action = "job", method = FormMethod.post, classes = "job-form") {
                div(classes = "job-form-row") {
                    label {
                        htmlFor = "job-form-version"
                        +"Version"
                    }
                    input(type = InputType.text) {
                        name = "version"
                        id = "job-form-version"
                        required = true
                        value = latestVersion
                    }
                }
                div(classes = "job-form-row") {
                    label {
                        htmlFor = "job-form-repository"
                        +"Repository"
                    }
                    input(type = InputType.text) {
                        name = "repository"
                        id = "job-form-repository"
                        required = true
                        workspace.gitRepositories.firstOrNull()?.url?.let { branch -> value = branch }
                    }
                }
                div(classes = "job-form-row") {
                    label {
                        htmlFor = "job-form-username"
                        +"Username"
                    }
                    input(type = InputType.text) {
                        name = "username"
                        id = "job-form-username"
                        required = true
                        workspace.gitRepositories.firstOrNull()?.credentials?.user?.let { username -> value = username }
                    }
                }
                div(classes = "job-form-row") {
                    label {
                        htmlFor = "job-form-password"
                        +"Password"
                    }
                    input(type = InputType.text) {
                        name = "password"
                        id = "job-form-password"
                        required = true
                        workspace.gitRepositories.firstOrNull()?.credentials?.password?.let { password ->
                            value = password
                        }
                    }
                }
                div(classes = "job-form-row") {
                    label {
                        htmlFor = "job-form-sourceRef"
                        +"Source reference"
                    }
                    input(type = InputType.text) {
                        name = "sourceRef"
                        id = "job-form-sourceRef"
                        required = true
                        workspace.gitRepositories.firstOrNull()?.branch?.let { branch -> value = branch }
                    }
                }
                div(classes = "job-form-row") {
                    label {
                        htmlFor = "job-form-targetBranch"
                        +"Target branch"
                    }
                    input(type = InputType.text) {
                        name = "targetBranch"
                        id = "job-form-targetBranch"
                        required = true
                        workspace.gitRepositories.firstOrNull()?.branch?.let { branch -> value = branch }
                    }
                }
                div(classes = "job-form-row") {
                    submitInput {
                        value = "Submit synchronization"
                    }
                }
            }
        }
    }
}
