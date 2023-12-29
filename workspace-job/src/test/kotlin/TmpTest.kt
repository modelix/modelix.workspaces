import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.modelix.buildtools.ModuleOrigin
import org.modelix.buildtools.ModulesMiner
import java.nio.file.Path

internal class SampleTest {


    @Test
    fun testSum() {
//        val expected = 42
//        assertEquals(42, 40 + 2)
        val moduleOrigin = ModuleOrigin(Path.of("/Users/odzhychko/Documents/arbeit1/customer_cpp/blueprint"))
        val modulesMiner = ModulesMiner()
        modulesMiner.searchInFolder(moduleOrigin)
    }
}