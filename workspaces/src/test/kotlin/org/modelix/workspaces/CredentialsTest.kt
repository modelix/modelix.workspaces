package org.modelix.workspaces

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CredentialsTest {

    private val credentialsEncryption = CredentialsEncryption("aKey")

    @Test
    fun testEncryption() {
        val credentials = Credentials("user", "1A.23bc\$")
        val encrypted = credentialsEncryption.encryptGitCredentials(credentials)
        val decrypted = credentialsEncryption.decryptGitCredentials(encrypted)
        assertEquals(credentials.password, decrypted.password)
        assertArrayEquals(credentials.password.toByteArray(), decrypted.password.toByteArray())
    }

    @Test
    fun testSerialization() {
        val credentials = Credentials("user", "1A.23bc\$")
        val serialized = Yaml.default.encodeToString(credentials)
        val deserialized = Yaml.default.decodeFromString<Credentials>(serialized)
        val encrypted = credentialsEncryption.encryptGitCredentials(deserialized)
        val serialized2 = Yaml.default.encodeToString(encrypted)
        val deserialized2 = Yaml.default.decodeFromString<Credentials>(serialized2)
        val decrypted = credentialsEncryption.decryptGitCredentials(deserialized2)
        assertEquals(credentials.password, decrypted.password)
        assertArrayEquals(credentials.password.toByteArray(), decrypted.password.toByteArray())
    }

}