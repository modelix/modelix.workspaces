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

package org.modelix.workspace.gitsync.checkout

import mu.KotlinLogging
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.JDKHttpConnection
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import org.modelix.model.api.*
import org.modelix.model.client2.ModelClientV2
import org.modelix.model.data.NodeData
import org.modelix.model.lazy.RepositoryId
import org.modelix.model.sync.bulk.ModelExporter
import org.modelix.workspaces.createCredentialEncryptionFromKubernetesSecret
import java.net.*
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries


private val LOG = KotlinLogging.logger {}

// TODO MODELIX-597 Use constantes from some shared code for Git sync
private val MODEL_DATA_DIRECTORY = Path.of("/model-data")
private val GIT_DATA_DIRECTORY = Path.of("/git-data")

suspend fun main(args: Array<String>) {
    val credentialsEncryption = createCredentialEncryptionFromKubernetesSecret()
    // TODO MODELIX-597 Use util function to get envs with proper error messages
    val modelServerV2Url = System.getenv("MODELIX_MODEL_SERVER_V2_URL")!!
    val modelServerTokenEncrypted = System.getenv("MODELIX_MODEL_SERVER_TOKEN")!!
    val modelServerTokenDecrypted = credentialsEncryption.decrypt(modelServerTokenEncrypted)
    val workspaceId = System.getenv("GIT_SYNC_JOB_WORKSPACE_ID")!!
    val modelRepositoryIdString = System.getenv("GIT_SYNC_JOB_MODEL_REPOSITORY")!!
    val modelRepositoryId = RepositoryId(modelRepositoryIdString)
    val modelVersionHash = System.getenv("GIT_SYNC_JOB_MODEL_VERSION_HASH")!!
    val gitRepository = System.getenv("GIT_SYNC_JOB_GIT_REPOSITORY")!!
    val gitRepositoryUsernameEncrypted = System.getenv("GIT_SYNC_JOB_GIT_REPOSITORY_USERNAME")!!
    val gitRepositoryPasswordEncrypted = System.getenv("GIT_SYNC_JOB_GIT_REPOSITORY_PASSWORD")!!
    val gitRepositoryUsernameDecrypted = credentialsEncryption.decrypt(gitRepositoryUsernameEncrypted)
    val gitRepositoryPasswordDecrypted = credentialsEncryption.decrypt(gitRepositoryPasswordEncrypted)
    // TODO MODELIX-597 make GIT_SYNC_JOB_GIT_SOURCE_REF to GIT_SYNC_JOB_GIT_SOURCE_BRANCH
    // TODO MODELIX-597 Use constants from some shared code for Git sync
    val gitSourceBranch = System.getenv("GIT_SYNC_JOB_GIT_SOURCE_REF")!!
    val gitTargetBranch = System.getenv("GIT_SYNC_JOB_GIT_TARGET_BRANCH")!!

    LOG.info { "Checking out data for Git synchronization for workspace `$workspaceId`." }
    LOG.info { "Checking out Modelix model with version `$modelVersionHash`." }
    checkoutModel(modelServerV2Url, modelServerTokenDecrypted, modelRepositoryId, modelVersionHash)

    LOG.info { "Checking out Git repository `$gitRepository`." }
    // TODO MODELIX-597 deduplicate implementation of clone with credential configuration, create some shared lib
    val cmd = Git.cloneRepository()
    .setCredentialsProvider(UsernamePasswordCredentialsProvider(gitRepositoryUsernameDecrypted, gitRepositoryPasswordDecrypted))
    .setTransportConfigCallback { transport ->
        transport?.setAuthenticator(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(gitRepositoryUsernameDecrypted, gitRepositoryPasswordDecrypted.toCharArray())
            }
        })
    }
    .setURI(gitRepository)
        .setBranchesToClone(listOf("refs/heads/$gitSourceBranch"))
        .setDepth(1)
        .setBranch(gitSourceBranch)
    .setDirectory(GIT_DATA_DIRECTORY.toFile())
    .call()
}

private suspend fun checkoutModel(
    modelServerV2Url: String,
    modelServerToken: String,
    modelRepositoryId: RepositoryId,
    modelVersionHash: String
) {
    LOG.info { "Creating model client for `$modelServerV2Url`." }
    val modelClient = ModelClientV2.builder()
        .url(modelServerV2Url)
        .authToken { modelServerToken }
        .build()

    modelClient.use {
        LOG.info { "Initializing model client." }
        modelClient.init()
        LOG.info { "Loading model version `$modelVersionHash`." }
        val versionData = modelClient.loadVersion(modelRepositoryId, modelVersionHash, null)


        LOG.info { "Saving model data for version `$modelVersionHash`." }
        val branch = TreePointer(versionData.getTree(), modelClient.getIdGenerator())

        // TODO MODELIX-597 continue here
        // TODO MODELIX-597 Transform data properly as needed for sync to Git.
        // TODO MODELIX-597 Data in server from legacy sync is name based. This should work but does not.
        // Might be some missing transformation or a bug.
        branch.runWrite {
            branch.getRootNode().getDescendants(true).forEach {
                val legacyOriginalId1 = it.getPropertyValue("\$originalId")
                if (legacyOriginalId1 != null) {
                    it.setPropertyValue(NodeData.ID_PROPERTY_KEY, legacyOriginalId1)
                    it.setPropertyValue("\$originalId", null)
                }
                // Is this necessary? Not sure if model adapters make exclusion for such node IDs.
                val legacyOriginalId2 = it.getPropertyValue("#mpsNodeId#")
                if (legacyOriginalId2 != null) {
                    it.setPropertyValue(NodeData.ID_PROPERTY_KEY, legacyOriginalId2)
                    it.setPropertyValue("#mpsNodeId#", null)
                }
            }
        }
        branch.runRead {
            val root = branch.getRootNode()
            getModules(root).forEach { module ->
                val moduleName = module.getPropertyValue(BuiltinLanguages.jetbrains_mps_lang_core.INamedConcept.name)
                requireNotNull(moduleName) {
                    "Module `$module` has no name."
                }
                val outputFile = MODEL_DATA_DIRECTORY.toFile().resolve("$moduleName.json")
                LOG.info { "Checking out module `$moduleName` from model server." }
                ModelExporter(module).export(outputFile)
            }
        }
    }
}

private fun getModules(root: INode): Iterable<INode> {
    val project = root.allChildren.single {
        val isProject = it.concept?.getUID() == BuiltinLanguages.MPSRepositoryConcepts.Project.getUID()
        isProject
    }
    val modules = project.allChildren
        .filter {
            val isModule = it.concept?.getUID() == BuiltinLanguages.MPSRepositoryConcepts.Module.getUID()
            isModule
        }
    return modules
}

// TODO MODELIX-597 deduplicate implementation, create some shared lib
/**
 * The credentialsProvider only works with WWW-Authenticate: Basic, but not with WWW-Authenticate: Negotiate.
 * This is handled by the JDK.
 */
private fun Transport.setAuthenticator(authenticator: Authenticator) {
    val transport = this as TransportHttp
    val originalFactory = transport.httpConnectionFactory as JDKHttpConnectionFactory
    transport.httpConnectionFactory = object : HttpConnectionFactory {
        override fun create(url: URL?): HttpConnection {
            return modify(originalFactory.create(url))
        }

        override fun create(url: URL?, proxy: Proxy?): HttpConnection {
            return modify(originalFactory.create(url, proxy))
        }

        fun modify(conn: HttpConnection): HttpConnection {
            val jdkConn = conn as JDKHttpConnection
            val field = jdkConn.javaClass.getDeclaredField("wrappedUrlConnection")
            field.isAccessible = true
            val wrapped = field.get(jdkConn) as HttpURLConnection
            wrapped.setAuthenticator(authenticator)
            return conn
        }
    }
}