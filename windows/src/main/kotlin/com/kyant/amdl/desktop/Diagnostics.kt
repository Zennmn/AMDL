package com.kyant.amdl.desktop

import com.kyant.amdl.engine.Cdm
import java.awt.GraphicsEnvironment

object Diagnostics {
    fun run() {
        println("AMDL Windows 1.1.6")
        println("Java: ${System.getProperty("java.version")}")
        println("OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEachIndexed { index, display ->
            val transform = display.defaultConfiguration.defaultTransform
            println("Display $index: ${display.displayMode.width}x${display.displayMode.height}, scale ${transform.scaleX}x${transform.scaleY}")
        }
        NativeLibrary.load().getOrThrow()
        println("Native library: loaded")
        val cdm = Cdm()
        try {
            println("Native engine: initialized")
        } finally {
            cdm.close()
        }
        println("Diagnostics: OK")
    }
}
