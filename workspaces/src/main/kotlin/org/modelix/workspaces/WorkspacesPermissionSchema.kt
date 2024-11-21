package org.modelix.workspaces

import org.modelix.authorization.permissions.PermissionParts
import org.modelix.authorization.permissions.PermissionSchemaBase
import org.modelix.authorization.permissions.buildPermissionSchema

object WorkspacesPermissionSchema {
    val SCHEMA = buildPermissionSchema {
        resource("workspaces") {

            permission("read-all-configs")

            permission("admin") {
                includedIn(PermissionSchemaBase.cluster.admin.parts[0], PermissionSchemaBase.cluster.admin.parts[1])
                permission("user") {
                    includedIn(PermissionSchemaBase.cluster.user.parts[0], PermissionSchemaBase.cluster.user.parts[1])
                    description("Can create and use their own workspaces")
                    permission("add") {
                        description("Can create a new workspace")
                    }
                }
            }

            resource("workspace") {
                parameter("id")

                resource("config") {
                    permission("delete") {
                        permission("write") {
                            permission("read") {
                                includedIn("workspaces", "read-all-configs")
                                includes("workspace", "list")
                            }
                        }
                    }

                }

                resource("model-repository") {
                    permission("write") {
                        permission("read")
                    }
                }

                resource("instance") {
                    permission("run") {
                        includes("config", "read")
                        includes("build-result", "read")
                    }
                }

                resource("shared-instance") {
                    permission("access")
                }

                resource("build-result") {
                    permission("write") {
                        includedIn("workspace", "owner")
                        permission("read")
                    }
                }

                permission("owner") {
                    includedIn("workspaces", "admin")
                    permission("delete") {
                        includes("config", "delete")
                    }
                    permission("maintainer") {
                        includes("config", "write")
                        includes("shared-instance", "access")
                        permission("contributor") {
                            includes("model-repository", "write")
                            permission("viewer") {
                                includes("config", "read")
                                includes("model-repository", "read")
                                includes("instance", "run")
                                permission("list")
                            }
                        }
                    }
                }
            }

            resource("uploads") {
                permission("add") {
                    includedIn("workspaces", "user")
                }
                resource("upload") {
                    parameter("id")
                    permission("read")
                    permission("delete")
                }
            }
        }
    }

    object workspaces {
        val resource = PermissionParts("workspaces")
        val readAllConfigs = resource + "read-all-configs"
        val admin = resource + "admin"
        val user = resource + "user"
        val add = resource + "add"

        fun workspace(id: String) = Workspace(id)

        class Workspace(val id: String) {
            val resource = workspaces.resource + "workspace" + id

            val owner = resource + "owner"
            val maintainer = resource + "maintainer"
            val contributor = resource + "contributor"
            val viewer = resource + "viewer"
            val delete = resource + "delete"
            val list = resource + "list"

            val config = Config()
            inner class Config {
                val resource = this@Workspace.resource + "config"
                val delete = resource + "delete"
                val write = resource + "write"
                val read = resource + "read"
            }

            val modelRepository = ModelRepository()
            inner class ModelRepository {
                val resource = this@Workspace.resource + "model-repository"
                val write = resource + "write"
                val read = resource + "read"
            }

            val instance = Instance()
            inner class Instance {
                val resource = this@Workspace.resource + "instance"
                val run = resource + "run"
            }

            val sharedInstance = SharedInstance()
            inner class SharedInstance {
                val resource = this@Workspace.resource + "shared-instance"
                val access = resource + "access"
            }

            val buildResult = BuildResult()
            inner class BuildResult {
                val resource = this@Workspace.resource + "build-result"
                val write = resource + "access"
                val read = resource + "access"
            }
        }

        object uploads {
            val resource = workspaces.resource + "uploads"
            val add = resource + "add"

            fun upload(id: String) = Upload(id)

            class Upload(val id: String) {
                val resource = uploads.resource + "upload" + id
                val read = resource + "read"
                val delete = resource + "delete"
            }
        }
    }
}
