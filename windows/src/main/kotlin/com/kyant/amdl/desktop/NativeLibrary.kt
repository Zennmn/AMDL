package com.kyant.amdl.desktop

import java.io.File
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

object NativeLibrary {
    private var loaded = false

    @Synchronized
    fun load(): Result<Unit> = runCatching {
        if (loaded) return@runCatching
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "此版本需要 Windows 10 或更新版本。"
        }
        check(System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")) {
            "此版本需要 Windows x64 和 64 位 Java 运行时。"
        }
        val explicitPath = System.getProperty("amdl.native.path")
        val library = if (!explicitPath.isNullOrBlank()) {
            File(explicitPath).also { require(it.isFile) { "找不到下载引擎。" } }
        } else {
            val data = NativeLibrary::class.java.getResourceAsStream("/native/windows-x64/amdl.dll")
                ?.use { it.readBytes() }
                ?: error("下载引擎文件缺失，请重新安装 AMDL。")
            val hash = MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }.take(16)
            val nativeDirectory = File(DesktopPaths.appData, "native")
            Files.createDirectories(nativeDirectory.toPath())
            File(nativeDirectory, "amdl-$hash.dll").also { target ->
                FileChannel.open(File(nativeDirectory, "amdl-$hash.lock").toPath(), CREATE, WRITE).use { channel ->
                    channel.lock().use {
                        if (!target.isFile) {
                            val temporary = Files.createTempFile(nativeDirectory.toPath(), "amdl-", ".tmp")
                            try {
                                Files.write(temporary, data)
                                Files.move(temporary, target.toPath())
                            } finally {
                                Files.deleteIfExists(temporary)
                            }
                        }
                    }
                }
            }
        }
        System.load(library.absolutePath)
        loaded = true
    }
}
