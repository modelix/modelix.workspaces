package org.modelix.workspaces

import org.jasypt.util.text.AES256TextEncryptor
import java.io.File

fun createCredentialEncryptionFromKubernetesSecret(): CredentialsEncryption {
    // Secrets mounted as files are more secure than environment variables
    // because environment variables can more easily leak or be extracted.
    // See https://stackoverflow.com/questions/51365355/kubernetes-secrets-volumes-vs-environment-variables
    val credentialsEncryptionKeyFile = File("/secrets/workspacesecret/workspace-credentials-key.txt")
    val credentialsEncryptionKey = credentialsEncryptionKeyFile.readLines().first()
    return CredentialsEncryption(credentialsEncryptionKey)
}
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

    fun encrypt(input: String): String {
        return if (input.startsWith(ENCRYPTED_PREFIX)) {
            input
        } else {
            ENCRYPTED_PREFIX + encryptor.encrypt(input)
        }
    }

    fun decrypt(input: String): String {
        return if (input.startsWith(ENCRYPTED_PREFIX)) {
            encryptor.decrypt(input.drop(ENCRYPTED_PREFIX.length))
        } else {
            input
        }
    }
}