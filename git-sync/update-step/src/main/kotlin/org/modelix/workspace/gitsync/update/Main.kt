/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.modelix.workspace.gitsync.update

import org.apache.tools.ant.DefaultLogger
import org.apache.tools.ant.Project
import org.apache.tools.ant.ProjectHelper
import org.modelix.buildtools.runner.MPSRunner
import org.modelix.buildtools.runner.MPSRunnerConfig
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

const val RUN_MPS_TASK_NAME = "run"

// TODO MODELIX-597 add use shared code for git-sync
private val MODEL_DATA_DIRECTORY = Path.of("/model-data")
private val GIT_DATA_DIRECTORY = Path.of("/git-data")
private val MPS_DIRECTORY = Path.of("/mps")
private val MPS_DEPENDENCIES = Path.of("/mpsDependencies")

fun main(args: Array<String>) {

    println("MODEL_DATA_DIRECTORY")
    println(
        MODEL_DATA_DIRECTORY.listDirectoryEntries().joinToString(System.lineSeparator())
    )
    println("MPS_DEPENDENCIES")
    println(
        MPS_DEPENDENCIES.listDirectoryEntries().joinToString(System.lineSeparator())
    )
    println("GIT_DATA_DIRECTORY")
    println(
        GIT_DATA_DIRECTORY.listDirectoryEntries().joinToString(System.lineSeparator())
    )
    println("MPS_DIRECTORY")
    println(
        MPS_DIRECTORY.listDirectoryEntries().joinToString(System.lineSeparator())
    )
    // TODO MODELIX-597 Find correct logger, Kotlin logging seems somehow to collide with logging from MPS started with ANT
//    LOG.info { "Updating files in Git repository." }


    // TODO MODELIX-597 remove test codee
    // Create token to interact with workspace manager
    // Download workspace configuration
    val newFile = GIT_DATA_DIRECTORY.resolve("test.md")
    newFile.writeText("# Test work\n\nyea")


    val jarForMps = MPS_DEPENDENCIES.listDirectoryEntries().map(Path::toFile)
    val moduleNames = MODEL_DATA_DIRECTORY.listDirectoryEntries().map {
        entry -> entry.fileName.toString().removeSuffix(".json")
    }
//    LOG.info { "The following modules will be imported: $moduleNames" }
    val config = MPSRunnerConfig(
        mainClassName = "org.modelix.mps.model.sync.bulk.MPSBulkSynchronizer",
        mainMethodName = "importRepository",
        classPathElements = jarForMps,
        mpsHome = MPS_DIRECTORY.toFile(),
        workDir = MODEL_DATA_DIRECTORY.toFile(),
        additionalModuleDirs = listOf(GIT_DATA_DIRECTORY.toFile()),
        jvmArgs = listOfNotNull(
            "-Dmodelix.mps.model.sync.bulk.input.path=${MODEL_DATA_DIRECTORY.toFile().absolutePath}",
            "-Dmodelix.mps.model.sync.bulk.input.modules=${moduleNames.joinToString(",")}",
            "-Dmodelix.mps.model.sync.bulk.input.modules.prefixes=",
            "-Dmodelix.mps.model.sync.bulk.repo.path=${GIT_DATA_DIRECTORY.toFile().absolutePath}",
            "-Dmodelix.mps.model.sync.bulk.input.continueOnError=false",
            // TODO MODELIX-597 maybe make it configurable or adapt is to setting of workspace (aka. use workspace settings memory limit here)
            "-Xmx4g",
        ),
    )
    val runner = MPSRunner(config)
    runner.generateAll()

    // See https://ant.apache.org/manual/projecthelper.html
    val generatedAntFile = runner.getAntScriptFile()
    val antProject = Project()

    println(runner.getAntScriptFile().readText())
    println(runner.getSolutionFile().readText())

    val projectHelper = ProjectHelper.getProjectHelper()
    projectHelper.parse(antProject, generatedAntFile)

    val antLogger = DefaultLogger()
    antLogger.setOutputPrintStream(System.out)
    antLogger.setErrorPrintStream(System.err)
    antLogger.messageOutputLevel = Project.MSG_INFO
    antProject.addBuildListener(antLogger)

    antProject.executeTarget(RUN_MPS_TASK_NAME)

    // TODO MODELIX-597 continue here
    // TODO MODELIX-597 failing sync not detected due to bug in modelix
    // (should be fixed upgrade to Modelix https://github.com/modelix/modelix.core/releases/tag/8.17.2
    // TODO MODELIX-597 upgrading modelix fails because model server does not startup (https://github.com/modelix/modelix.core/pull/1039)
    // TODO MODELIX-597 upgrading modelix fails due to new authentication being enabled in V2 when Keycloak URL is configured
}