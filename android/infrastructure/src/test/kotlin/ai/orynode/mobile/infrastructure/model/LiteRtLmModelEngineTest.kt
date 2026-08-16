package ai.orynode.mobile.infrastructure.model

import ai.orynode.mobile.domain.AnalysisRequest
import ai.orynode.mobile.domain.ModelRuntimeError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertFailsWith

class LiteRtLmModelEngineTest {
    @Test
    fun generateBeforeLoadThrowsEngineNotReady() = runTest {
        val cache = Files.createTempDirectory("litertlm-cache")
        val engine = LiteRtLmModelEngine(cacheDir = cache, preferGpu = false)
        val error = assertFailsWith<ModelRuntimeError.EngineNotReady> {
            engine.generate(AnalysisRequest("hello"))
        }
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun loadMissingFileThrowsModelNotInstalled() = runTest {
        val cache = Files.createTempDirectory("litertlm-cache")
        val engine = LiteRtLmModelEngine(cacheDir = cache, preferGpu = false)
        val missing = cache.resolve("missing.litertlm")
        assertFailsWith<ModelRuntimeError.ModelNotInstalled> {
            engine.load(missing)
        }
    }
}
