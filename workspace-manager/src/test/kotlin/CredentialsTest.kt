import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.modelix.workspace.manager.CredentialsEncryption
import org.modelix.workspaces.Credentials

class CredentialsTest {

    private val credentialsEncryption = CredentialsEncryption("aKey")

    @Test
    fun testEncryption() {
        val credentials = Credentials("user", "1A.23bc\$")
        val encrypted = credentialsEncryption.encrypt(credentials)
        val decrypted = credentialsEncryption.decrypt(encrypted)
        Assertions.assertEquals(credentials.password, decrypted.password)
        Assertions.assertArrayEquals(credentials.password.toByteArray(), decrypted.password.toByteArray())
    }

    @Test
    fun testSerialization() {
        val credentials = Credentials("user", "1A.23bc\$")
        val serialized = Yaml.default.encodeToString(credentials)
        val deserialized = Yaml.default.decodeFromString<Credentials>(serialized)
        val encrypted = credentialsEncryption.encrypt(deserialized)
        val serialized2 = Yaml.default.encodeToString(encrypted)
        val deserialized2 = Yaml.default.decodeFromString<Credentials>(serialized2)
        val decrypted = credentialsEncryption.decrypt(deserialized2)
        Assertions.assertEquals(credentials.password, decrypted.password)
        Assertions.assertArrayEquals(credentials.password.toByteArray(), decrypted.password.toByteArray())
    }

}