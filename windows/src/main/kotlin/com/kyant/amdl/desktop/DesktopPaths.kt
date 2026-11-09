package com.kyant.amdl.desktop

import java.io.File

object DesktopPaths {
    val appData: File = File(
        System.getenv("LOCALAPPDATA") ?: File(System.getProperty("user.home"), "AppData/Local").path,
        "AMDL"
    )
    val defaultDownloadDirectory: File = File(System.getProperty("user.home"), "Music/AMDL")
}
