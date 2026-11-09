package com.kyant.amdl.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object DesktopIcons {
    // Miuix Recent is filled. Keep its clock silhouette and 24dp optical bounds,
    // with a rounded 1.8-unit outline matching the adjacent regular rail glyphs.
    val HistoryOutline: ImageVector = ImageVector.Builder(
        name = "AMDL.HistoryOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 2.9f)
            curveTo(17.026f, 2.9f, 21.1f, 6.974f, 21.1f, 12f)
            curveTo(21.1f, 17.026f, 17.026f, 21.1f, 12f, 21.1f)
            curveTo(6.974f, 21.1f, 2.9f, 17.026f, 2.9f, 12f)
            curveTo(2.9f, 6.974f, 6.974f, 2.9f, 12f, 2.9f)
            close()
            moveTo(12f, 5.7f)
            verticalLineTo(12f)
            horizontalLineTo(17.1f)
        }
    }.build()
}
