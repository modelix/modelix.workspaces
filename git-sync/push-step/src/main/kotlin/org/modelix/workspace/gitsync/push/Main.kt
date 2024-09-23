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

package org.modelix.workspace.gitsync.push

import mu.KotlinLogging
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.JDKHttpConnection
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import org.modelix.workspaces.createCredentialEncryptionFromKubernetesSecret
import java.net.*
import java.nio.file.Path


private val LOG = KotlinLogging.logger {}

// TODO MODELIX-597 Use constantes from some shared code for Git sync

private val GIT_DATA_DIRECTORY = Path.of("/git-data")

fun main(args: Array<String>) {
    val credentialsEncryption = createCredentialEncryptionFromKubernetesSecret()
    val gitRepositoryUsernameEncrypted = System.getenv("GIT_SYNC_JOB_GIT_REPOSITORY_USERNAME")!!
    val gitRepositoryPasswordEncrypted = System.getenv("GIT_SYNC_JOB_GIT_REPOSITORY_PASSWORD")!!
    val gitRepositoryUsernameDecrypted = credentialsEncryption.decrypt(gitRepositoryUsernameEncrypted)
    val gitRepositoryPasswordDecrypted = credentialsEncryption.decrypt(gitRepositoryPasswordEncrypted)
//    val gitTargetBranch = System.getenv("GIT_SYNC_JOB_GIT_TARGET_BRANCH")!!

    LOG.info { "Pushing data to Git repository." }
    val repository = Git.open(GIT_DATA_DIRECTORY.toFile())
    repository.use {

        LOG.info { "Add changed files to Git." }
        repository.add()
            .addFilepattern(".")
            .call()

        LOG.info { "Commiting changed files to Git." }
        repository.commit()
            // TODO MODELIX-597 set mail betteer
            // Try empty mail https://stackoverflow.com/questions/7372970/git-commit-with-no-email or null
            .setAuthor(PersonIdent("Modelix Workspace Git-Sync-Job", "test@example.com"))
            .setMessage("git-sync job add details\n\nMore details\nEven more details\n")
            .call()

        LOG.info { "Pushing commit to remote repository." }
        val pushResult = repository.push()
            .setRemote("origin")
            .setCredentialsProvider(UsernamePasswordCredentialsProvider(gitRepositoryUsernameDecrypted, gitRepositoryPasswordDecrypted))
            .setTransportConfigCallback { transport ->
                transport?.setAuthenticator(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(
                            gitRepositoryUsernameDecrypted,
                            gitRepositoryPasswordDecrypted.toCharArray()
                        )
                    }
                })
            }
            // TODO MODELIX-597 use target branch
            .setRefSpecs(listOf(RefSpec("refs/heads/master:refs/heads/test-source-git-sync-job")))
            .call()

        LOG.info { "Push result in the following updates ${listOf(pushResult.toList().flatMap { it.remoteUpdates })}" }
    }
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