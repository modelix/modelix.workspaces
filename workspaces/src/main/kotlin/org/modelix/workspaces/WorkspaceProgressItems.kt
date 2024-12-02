package org.modelix.workspaces

private val STATUS_MESSAGE_REGEX = Regex("### ([A-Z]+) ([a-zA-Z-]+) ###")

class WorkspaceProgressItems() {
    private val itemsById: MutableMap<String, ProgressItem> = LinkedHashMap()
    val build = Build()
    val container = Container()

    fun parseLog(log: String) {
        for (matchResult in STATUS_MESSAGE_REGEX.findAll(log)) {
            val status = matchResult.groups[1]!!.value
            val id = matchResult.groups[2]!!.value
            val item = itemsById[id] ?: continue
            when (status) {
                "START" -> item.started = true
                "DONE" -> item.done = true
                "FAILED" -> item.failed = true
                else -> {
                    // It's not guaranteed that messages are only produced by the workspace job
                }
            }
        }
    }

    fun getItems(): Map<String, List<ProgressItem>> {
        return itemsById.values.groupBy { it.id.substringBefore("-") }
    }

    inner class Build {
        val enqueue = ProgressItem("build-enqueue", "Enqueue build job")
        val startKubernetesJob = ProgressItem("build-startKubernetesJob", "Start Kubernetes job")
        val gitClone = ProgressItem("build-gitClone", "Clone Git repositories")
        val downloadMavenDependencies = ProgressItem("build-downloadMavenDependencies", "Download dependencies from Maven")
        val copyUploads = ProgressItem("build-copyUploads", "Copy uploads")
        val generateBuildScript = ProgressItem("build-generateBuildScript", "Analyze MPS module dependencies")
        val buildMpsModules = ProgressItem("build-buildMpsModules", "Build MPS modules")
        val packageResult = ProgressItem("build-packageResult", "Package build result")
        val uploadResult = ProgressItem("build-uploadResult", "Upload build result")
    }

    inner class Container {
        val createDeployment = ProgressItem("container-createDeployment", "Create Kubernetes deployment")
        val startContainer = ProgressItem("container-startContainer", "Start Container")
        val prepareMPS = ProgressItem("container-prepareMPS", "Prepare MPS project")
        val startMPS = ProgressItem("container-startMPS", "Start MPS")
        val runIndexer = ProgressItem("container-runIndexer", "Update project indexes")
    }

    inner class ProgressItem(
        val id: String,
        val description: String,
        var done: Boolean = false,
        var failed: Boolean = false,
        var started: Boolean = false
    ) {
        init {
            itemsById[id] = this
        }

        fun statusText(): String {
            return when {
                failed -> "failed"
                done -> "done"
                started -> "in progress"
                else -> ""
            }
        }

        fun logStart() {
            println("### START $id ###")
        }
        fun logDone() {
            println("### DONE $id ###")
        }
        fun logFailed() {
            println("### FAILED $id ###")
        }

        inline fun <R> execute(body: () -> R): R {
            try {
                logStart()
                return body()
            } catch (ex: Throwable) {
                logFailed()
                throw ex
            } finally {
                logDone()
            }
        }
    }
}