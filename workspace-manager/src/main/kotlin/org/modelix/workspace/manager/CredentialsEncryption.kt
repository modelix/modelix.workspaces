package org.modelix.workspace.manager

import io.ktor.http.takeFrom
import io.ktor.server.util.url
import org.jasypt.util.text.AES256TextEncryptor
import org.modelix.workspaces.Credentials
import org.modelix.workspaces.GitRepository
import org.modelix.workspaces.Workspace

/**
 *
 */
class CredentialsEncryption(key: String) {
    companion object {
        private const val ENCRYPTED_PREFIX = "encrypted:"
    }

    private val encryptor = AES256TextEncryptor()

    init {
        encryptor.setPassword(key)
    }

    fun decrypt(credentials: Credentials): Credentials {
        return Credentials(decrypt(credentials.user), decrypt(credentials.password))
    }

    fun encrypt(credentials: Credentials): Credentials {
        return Credentials(encrypt(credentials.user), encrypt(credentials.password))
    }

    private fun encrypt(input: String): String {
        return if (input.startsWith(ENCRYPTED_PREFIX)) {
            input
        } else {
            ENCRYPTED_PREFIX + encryptor.encrypt(input)
        }
    }

    private fun decrypt(input: String): String {
        return if (input.startsWith(ENCRYPTED_PREFIX)) {
            encryptor.decrypt(input.drop(ENCRYPTED_PREFIX.length))
        } else {
            input
        }
    }
}

fun CredentialsEncryption.copyWithEncryptedCredentials(workspace: Workspace): Workspace =
    workspace.copy(gitRepositories = workspace.gitRepositories.map(::copyWithEncryptedCredentials))

fun CredentialsEncryption.copyWithDecryptedCredentials(workspace: Workspace): Workspace =
    workspace.copy(gitRepositories = workspace.gitRepositories.map(::copyWithDecryptedCredentials))

fun CredentialsEncryption.copyWithEncryptedCredentials(gitRepository: GitRepository): GitRepository =
    gitRepository.copy(credentials = gitRepository.credentials?.run(::encrypt))

fun CredentialsEncryption.copyWithDecryptedCredentials(gitRepository: GitRepository): GitRepository =
    gitRepository.copy(credentials = gitRepository.credentials?.run(::decrypt))

fun GitRepository.urlWithCredentials(ce: CredentialsEncryption): String {
    val decryptedCredentials = ce.decrypt(credentials ?: return url)
    return url {
        takeFrom(url)
        user = decryptedCredentials.user
        password = decryptedCredentials.password
    }
}
