package com.kyant.amdl.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.LocalMiuixWindowInfo
import top.yukonga.miuix.kmp.window.LocalWindowState
import top.yukonga.miuix.kmp.window.MiuixCaptionBar
import top.yukonga.miuix.kmp.window.MiuixWindow
import top.yukonga.miuix.kmp.window.MiuixWindowBackgroundType
import top.yukonga.miuix.kmp.window.MiuixWindowProperties
import top.yukonga.miuix.kmp.window.WindowDraggableArea

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    System.setProperty("file.encoding", "UTF-8")
    if ("--auth-diagnostics" in args) {
        AuthenticationDiagnostics.run(args.getOrNull(args.indexOf("--auth-diagnostics") + 1))
        return
    }
    if ("--diagnostics" in args) {
        Diagnostics.run()
        return
    }
    application {
        val state = remember { DesktopState() }
        val themeController = remember {
            ThemeController(
                colorSchemeMode = ColorSchemeMode.System,
            )
        }
        DisposableEffect(state) { onDispose { state.close() } }
        MiuixTheme(controller = themeController) {
            MiuixWindow(
                title = "AMDL",
                state = rememberWindowState(width = 1180.dp, height = 800.dp),
                icon = painterResource("amdl.png"),
                onCloseRequest = ::exitApplication,
                properties = MiuixWindowProperties.default(
                    minSize = DpSize(940.dp, 650.dp),
                    backgroundType = MiuixWindowBackgroundType.None,
                ),
            ) {
                MiuixWindowContent { AmdlDesktop(state) }
            }
        }
    }
}

@Composable
internal fun MiuixWindowContent(content: @Composable () -> Unit) {
    val captionBarHeight = LocalMiuixWindowInfo.current.captionBarHeight
    val isFullscreen = LocalWindowState.current.placement == WindowPlacement.Fullscreen
    DesktopWindowFrame(
        captionBarHeight = if (isFullscreen) 0.dp else captionBarHeight,
        captionBar = {
            WindowDraggableArea()
            MiuixCaptionBar {
                DesktopCaptionBrand(Modifier.fillMaxSize())
            }
        },
        content = content,
    )
}
