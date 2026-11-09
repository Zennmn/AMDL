package com.kyant.amdl.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.VerticalDivider

internal val LocalSidebarDividerPosition = staticCompositionLocalOf<MutableIntState?> { null }

/** Places the native divider in window bounds, below the root Scaffold's popup layer. */
@Composable
internal fun DesktopWindowFrame(
    captionBarHeight: Dp,
    captionBar: @Composable BoxScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val dividerPosition = remember { mutableIntStateOf(-1) }
    var originX by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalSidebarDividerPosition provides dividerPosition) {
        Scaffold { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding).onPlaced {
                    originX = it.positionInRoot().x.roundToInt()
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (captionBarHeight > 0.dp) {
                        Box(Modifier.fillMaxWidth().height(captionBarHeight), content = captionBar)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
                if (dividerPosition.intValue >= 0) {
                    VerticalDivider(
                        Modifier
                            .offset { IntOffset(dividerPosition.intValue - originX, 0) }
                            .testTag("window-sidebar-divider"),
                    )
                }
            }
        }
    }
}
