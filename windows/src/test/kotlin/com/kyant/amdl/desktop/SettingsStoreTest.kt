package com.kyant.amdl.desktop

import com.kyant.amdl.api.AmTokens
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
class SettingsStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `settings round trip with Windows encrypted credentials and logout removes them`() {
        val store = SettingsStore(directory.toFile())
        val config = SettingsStore.defaultConfig().copy(downloadPath = "C:\\Music\\测试", preferAtmos = true)
        val tokens = AmTokens("test-developer-secret", "test-user-secret")
        store.save(config, tokens)
        val persisted = directory.resolve("settings.json").toFile().readText()
        assertFalse(persisted.contains(tokens.devToken))
        assertFalse(persisted.contains(tokens.mediaUserToken))
        assertTrue(persisted.contains("credentials"))
        val restored = store.load()
        assertEquals(config, restored.config)
        assertEquals(tokens, restored.tokens)
        store.save(config, AmTokens("", ""))
        assertEquals(AmTokens("", ""), store.load().tokens)
        assertFalse(directory.resolve("settings.json").toFile().readText().contains("credentials"))
    }

    @Test
    fun `invalid saved file is preserved and presents a recovery warning`() {
        val file = directory.resolve("settings.json").toFile()
        file.writeText("{ invalid settings")
        val restored = SettingsStore(directory.toFile()).load()
        assertNotNull(restored.warning)
        assertEquals("{ invalid settings", file.readText())
    }
}
