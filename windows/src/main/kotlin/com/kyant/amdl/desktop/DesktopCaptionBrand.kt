package com.kyant.amdl.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

internal const val CaptionBrandAreaTag = "caption-brand-area"
internal const val CaptionBrandIconTag = "caption-brand-icon"

private const val CaptionBrandExpandedMinWidthDp = 120f
private const val CaptionBrandHorizontalPaddingDp = 16f
private const val CaptionBrandIconSizeDp = 18f
private const val CaptionBrandIconGapDp = 10f
private const val CaptionBrandFallbackWidthDp = 80f

/**
 * The brand area follows the navigation rail's observed divider position.
 * While the rail is compact only the centered icon is visible; the wordmark
 * appears once the rail is wide enough to hold it with space before the divider.
 */
@Composable
internal fun DesktopCaptionBrand(modifier: Modifier = Modifier) {
    val dividerPosition = LocalSidebarDividerPosition.current
    var captionOriginX by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val widthPx = if (dividerPosition != null && dividerPosition.intValue >= 0) {
        val origin = captionOriginX.takeUnless { it == Int.MIN_VALUE } ?: 0
        (dividerPosition.intValue - origin).coerceAtLeast(0)
    } else {
        -1
    }
    val sidebarWidth = if (widthPx >= 0) {
        with(LocalDensity.current) { widthPx.toDp() }
    } else {
        CaptionBrandFallbackWidthDp.dp
    }

    Box(
        modifier.onPlaced { coordinates ->
            val origin = coordinates.positionInRoot().x.roundToInt()
            if (captionOriginX != origin) captionOriginX = origin
        },
    ) {
        CaptionBrandContent(sidebarWidth)
    }
}

@Composable
private fun CaptionBrandContent(sidebarWidth: Dp) {
    val expanded = sidebarWidth >= CaptionBrandExpandedMinWidthDp.dp
    Box(
        Modifier
            .width(sidebarWidth)
            .fillMaxHeight()
            .testTag(CaptionBrandAreaTag),
    ) {
        if (expanded) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = CaptionBrandHorizontalPaddingDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptionBrandIcon()
                Spacer(Modifier.width(CaptionBrandIconGapDp.dp))
                Text(
                    text = "AMDL",
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CaptionBrandIcon()
            }
        }
    }
}

@Composable
private fun CaptionBrandIcon() {
    Image(
        painter = painterResource("amdl.png"),
        contentDescription = null,
        modifier = Modifier
            .size(CaptionBrandIconSizeDp.dp)
            .testTag(CaptionBrandIconTag),
    )
}
