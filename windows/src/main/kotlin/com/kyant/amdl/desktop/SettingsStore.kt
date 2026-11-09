package com.kyant.amdl.desktop

import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.downloader.UserConfig
import com.sun.jna.platform.win32.Crypt32Util
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64

data class StoredSettings(
    val config: UserConfig,
    val tokens: AmTokens = AmTokens("", ""),
    val warning: String? = null
)

class SettingsStore(private val directory: File = DesktopPaths.appData) {
    private val settingsFile = File(directory, "settings.json")

    fun load(): StoredSettings {
        val defaults = defaultConfig()
        if (!settingsFile.isFile) return StoredSettings(defaults)
        return try {
            val root = JSONObject(settingsFile.readText(UTF_8))
            val config = UserConfig(
                language = root.optString("language", defaults.language),
                preferAtmos = root.optBoolean("preferAtmos", defaults.preferAtmos),
                downloadPath = root.optString("downloadPath", defaults.downloadPath),
                saveByAlbum = root.optBoolean("saveByAlbum", defaults.saveByAlbum),
                mergeSingles = root.optBoolean("mergeSingles", defaults.mergeSingles),
                saveTtml = root.optBoolean("saveTtml", defaults.saveTtml)
            )
            val encrypted = root.optString("credentials", "")
            if (encrypted.isBlank()) return StoredSettings(config)
            try {
                val bytes = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(encrypted))
                val credentials = JSONObject(String(bytes, UTF_8))
                bytes.fill(0)
                StoredSettings(
                    config,
                    AmTokens(credentials.optString("devToken", ""), credentials.optString("mediaUserToken", ""))
                )
            } catch (_: Exception) {
                StoredSettings(config, warning = "保存的登录信息已失效，请重新登录 Apple Music。")
            }
        } catch (_: Exception) {
            StoredSettings(defaults, warning = "无法读取设置，已使用默认配置。原设置文件仍保留在应用数据目录。")
        }
    }

    @Synchronized
    fun save(config: UserConfig, tokens: AmTokens) {
        Files.createDirectories(directory.toPath())
        val root = JSONObject()
            .put("version", 1)
            .put("language", config.language)
            .put("preferAtmos", config.preferAtmos)
            .put("downloadPath", config.downloadPath)
            .put("saveByAlbum", config.saveByAlbum)
            .put("mergeSingles", config.mergeSingles)
            .put("saveTtml", config.saveTtml)

        if (tokens.devToken.isNotBlank() && tokens.mediaUserToken.isNotBlank()) {
            val plain = JSONObject()
                .put("devToken", tokens.devToken)
                .put("mediaUserToken", tokens.mediaUserToken)
                .toString().toByteArray(UTF_8)
            val encrypted = try {
                Crypt32Util.cryptProtectData(plain)
            } finally {
                plain.fill(0)
            }
            root.put("credentials", Base64.getEncoder().encodeToString(encrypted))
        }

        val temporary = Files.createTempFile(directory.toPath(), "settings-", ".tmp")
        try {
            Files.writeString(temporary, root.toString(2), UTF_8)
            try {
                Files.move(temporary, settingsFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, settingsFile.toPath(), REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        fun defaultConfig() = UserConfig(
            language = "zh-Hans-CN",
            preferAtmos = false,
            downloadPath = DesktopPaths.defaultDownloadDirectory.path,
            saveByAlbum = true,
            mergeSingles = false,
            saveTtml = true
        )
    }
}
