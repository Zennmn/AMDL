package com.kyant.amdl.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.amdl.api.Track
import com.kyant.amdl.downloader.Task
import com.kyant.amdl.downloader.UserConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in, headless visual audit. This never creates or automates a desktop window.
 *
 * Run after the parent build is free:
 *   ./gradlew test --tests com.kyant.amdl.desktop.UiRenderSmokeTest -Damdl.ui.render=true
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "amdl.ui.render", matches = "true")
class UiRenderSmokeTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun renderUiAuditMatrix() {
        assertTrue(
            System.getProperty("java.awt.headless") == "true",
            "UI audit requires the test JVM headless mode",
        )
        val outputDirectory = File(System.getProperty("user.dir"), "build/ui-audit").apply {
            check(mkdirs() || isDirectory) { "Cannot create " + absolutePath }
        }
        val state = DesktopState(SettingsStore(tempDirectory.toFile()))
        val written = mutableListOf<String>()
        try {
            state.cancelBackgroundWork()
            state.applyNormalReadyState()

            // Light is first because it is the user-facing acceptance theme.
            for (theme in Themes) {
                for (viewport in Viewports) {
                    for (page in Pages) {
                        val name = "normal-" + page.slug + "-" + viewport.label + "-" + theme.slug
                        renderPage(
                            outputDirectory = outputDirectory,
                            fileName = name,
                            viewport = viewport,
                            dark = theme.dark,
                            state = state,
                            page = page.index,
                            written = written,
                        )
                    }
                }
            }

            val standard = Viewports.first()
            val narrow = Viewports.last()

            renderPage(
                outputDirectory,
                "rail-collapsed-home-light-" + standard.label,
                standard,
                false,
                state,
                0,
                written,
                prepare = { scene, session ->
                    scene.requireRailDescription(CollapseRailDescription, standard)
                    val expandedHeaderX = scene.requireSemanticTextLeft(HomeSubtitle, standard)
                    check(scene.clickRailToggle(CollapseRailDescription, standard, session)) {
                        "The home rail collapse toggle was not actionable"
                    }
                    scene.requireRailDescription(ExpandRailDescription, standard)
                    val collapsedHeaderX = scene.requireSemanticTextLeft(HomeSubtitle, standard)
                    assertEquals(
                        StandardContentShiftDp * RenderDensity,
                        expandedHeaderX - collapsedHeaderX,
                        RailWidthToleranceDp * RenderDensity,
                        "Manual collapse must widen the content by the rail delta",
                    )
                },
            )

            renderPage(
                outputDirectory,
                "rail-collapsed-settings-light-" + standard.label,
                standard,
                false,
                state,
                0,
                written,
                prepare = { scene, session ->
                    scene.requireRailDescription(CollapseRailDescription, standard)
                    val expandedHeaderX = scene.requireSemanticTextLeft(HomeSubtitle, standard)
                    check(scene.clickRailToggle(CollapseRailDescription, standard, session)) {
                        "The home rail collapse toggle was not actionable"
                    }
                    scene.requireRailDescription(ExpandRailDescription, standard)
                    val collapsedHeaderX = scene.requireSemanticTextLeft(HomeSubtitle, standard)
                    assertEquals(
                        StandardContentShiftDp * RenderDensity,
                        expandedHeaderX - collapsedHeaderX,
                        RailWidthToleranceDp * RenderDensity,
                        "Manual collapse must widen the content by the rail delta",
                    )
                    check(
                        scene.clickSemanticText(
                            predicate = { it == "设置" },
                            widthPx = standard.pixelWidth,
                            heightPx = standard.pixelHeight,
                            session = session,
                        ),
                    ) { "The settings rail item was not actionable" }
                    session.pump(6)
                    scene.requireRailDescription(ExpandRailDescription, standard)
                    val settingsHeaderX = scene.requireSemanticTextLeft(SettingsSubtitle, standard)
                    assertEquals(
                        collapsedHeaderX,
                        settingsHeaderX,
                        RailWidthToleranceDp * RenderDensity,
                        "Switching to settings must preserve the manually collapsed rail",
                    )
                },
            )

            renderPage(
                outputDirectory,
                "rail-expanded-home-light-" + narrow.label,
                narrow,
                false,
                state,
                0,
                written,
                prepare = { scene, session ->
                    scene.requireRailDescription(ExpandRailDescription, narrow)
                    val collapsedHeaderX = scene.requireSemanticTextLeft(HomeSubtitle, narrow)
                    check(scene.clickRailToggle(ExpandRailDescription, narrow, session)) {
                        "The home rail expand toggle was not actionable"
                    }
                    scene.requireRailDescription(CollapseRailDescription, narrow)
                    val expandedHeaderX = scene.requireSemanticTextLeft(HomeSubtitle, narrow)
                    assertEquals(
                        RailWidthShiftDp * RenderDensity,
                        expandedHeaderX - collapsedHeaderX,
                        RailWidthToleranceDp * RenderDensity,
                        "Manual expand must narrow the content by the rail delta",
                    )
                },
            )

            state.applyLoggedOutState()
            renderPage(
                outputDirectory,
                "state-home-loggedout-light-" + standard.label,
                standard,
                false,
                state,
                0,
                written,
            )

            state.applyLoadingState()
            for (viewport in Viewports) {
                renderPage(
                    outputDirectory,
                    "state-home-loading-light-" + viewport.label,
                    viewport,
                    false,
                    state,
                    0,
                    written,
                )
            }

            state.applyNativeErrorState()
            renderPage(
                outputDirectory,
                "state-home-native-error-light-" + standard.label,
                standard,
                false,
                state,
                0,
                written,
            )

            state.applyQueueFilledState()
            renderPage(
                outputDirectory,
                "state-queue-filled-light-" + standard.label,
                standard,
                false,
                state,
                1,
                written,
            )
            renderPage(
                outputDirectory,
                "state-queue-failed-light-" + standard.label,
                standard,
                false,
                state,
                1,
                written,
                prepare = { scene, session ->
                    val clicked = scene.clickSemanticText(
                        predicate = { it.startsWith("失败 (") },
                        widthPx = standard.pixelWidth,
                        heightPx = standard.pixelHeight,
                        session = session,
                    )
                    check(clicked) { "Queue failed-tab semantics were not found" }
                },
            )

            state.applyHistoryFilledState()
            renderPage(
                outputDirectory,
                "state-history-filled-light-" + standard.label,
                standard,
                false,
                state,
                2,
                written,
            )

            state.applySettingsState()
            // 1180x768 is the content area of the reported 1180x800 window, and
            // 940x618 is the content area of the 940x650 minimum window.
            for (viewport in Viewports) {
                renderPage(
                    outputDirectory,
                    "settings-bottom-light-" + viewport.label,
                    viewport,
                    false,
                    state,
                    3,
                    written,
                    prepare = { scene, session ->
                        check(scene.scrollToBottom(viewport, session)) {
                            "Settings scroll semantics were not found at " + viewport.label
                        }
                        scene.requireSettingsBottom(viewport)
                    },
                )
            }
            renderPage(
                outputDirectory,
                "settings-language-menu-light-" + standard.label,
                standard,
                false,
                state,
                3,
                written,
                prepare = { scene, session ->
                    check(scene.openLanguageMenu(standard, session)) {
                        "Settings language menu semantics were not found"
                    }
                },
            )

            val wide = Viewport(1600, 900)
            renderPage(
                outputDirectory,
                "wide-settings-light-" + wide.label,
                wide,
                false,
                state,
                3,
                written,
                prepare = { scene, _ ->
                    scene.requireWideSettingsLayout(wide)
                },
            )

            state.applyNormalReadyState()
            val frame = Viewport(1180, 800)
            renderFrame(
                outputDirectory = outputDirectory,
                fileName = "frame-expanded-home-light-" + frame.label,
                viewport = frame,
                dark = false,
                state = state,
                written = written,
                prepare = { scene, _ ->
                    scene.requireRailDescription(CollapseRailDescription, frame)
                    scene.requireExpandedFrameLayout(frame)
                    scene.requireWindowSidebarDivider(frame)
                    scene.requireExpandedCaptionBrand(frame)
                },
            )
            renderFrame(
                outputDirectory = outputDirectory,
                fileName = "frame-collapsed-home-light-" + frame.label,
                viewport = frame,
                dark = false,
                state = state,
                written = written,
                prepare = { scene, session ->
                    scene.requireRailDescription(CollapseRailDescription, frame)
                    check(scene.clickRailToggle(CollapseRailDescription, frame, session)) {
                        "The frame rail collapse toggle was not actionable"
                    }
                    scene.requireRailDescription(ExpandRailDescription, frame)
                    scene.requireCollapsedFrameLayout(frame)
                    scene.requireWindowSidebarDivider(frame)
                    scene.requireCollapsedCaptionBrand(frame)
                },
            )
            val folders = createFolderFixtures(outputDirectory)
            val popupFrame = Viewport(940, 650)
            val showFolder = mutableStateOf(false)
            val popupFrameName = "frame-folder-over-divider-light-" + popupFrame.label
            capture(
                outputDirectory = outputDirectory,
                fileName = popupFrameName,
                viewport = popupFrame,
                dark = false,
                written = written,
                prepare = { scene, session ->
                    check(scene.clickRailToggle(ExpandRailDescription, popupFrame, session))
                    scene.requireWindowSidebarDivider(popupFrame)
                    showFolder.value = true
                    session.pump(12)
                },
            ) {
                DesktopWindowFrame(
                    captionBarHeight = 32.dp,
                    captionBar = { Text("AMDL") },
                ) {
                    AmdlDesktop(state, initialPage = 3)
                    if (showFolder.value) {
                        MiuixDirectoryPicker(folders.first().path, onDismiss = {}, onSelect = {})
                    }
                }
            }
            // The expanded boundary crosses the centered dialog on a narrow window.
            // Its top interior must cover the divider rather than drawing a line over it.
            val popupPixels = javax.imageio.ImageIO.read(File(outputDirectory, "$popupFrameName.png"))
            val coveredPixel = popupPixels.getRGB(
                (240 * RenderDensity).toInt(),
                (popupFrame.pixelHeight / 6 + 20),
            )
            assertTrue(
                listOf(16, 8, 0).all { shift -> (coveredPixel shr shift and 0xff) >= 250 },
                "The dialog must cover the window divider",
            )
            for (viewport in Viewports) {
                for (folder in if (viewport == standard) folders else folders.take(1)) {
                captureOverlay(
                    outputDirectory = outputDirectory,
                    fileName = "dialog-folder-" + folder.slug + "-light-" + viewport.label,
                    viewport = viewport,
                    dark = false,
                    state = state,
                    written = written,
                ) {
                    MiuixDirectoryPicker(
                        currentPath = folder.path,
                        onDismiss = {},
                        onSelect = {},
                    )
                }
            }
            }
            for (viewport in Viewports) {
            captureOverlay(
                outputDirectory = outputDirectory,
                fileName = "dialog-logout-light-" + viewport.label,
                viewport = viewport,
                dark = false,
                state = state,
                written = written,
            ) {
                LogoutConfirmation(onDismiss = {}, onConfirm = {})
            }
            }
        } finally {
            state.close()
        }

        assertEquals(ExpectedRenderCount, written.size)
        assertEquals(written.size, written.toSet().size)
        written.forEach { name ->
            val image = File(outputDirectory, name)
            assertTrue(image.isFile && image.length() > 0L, "Missing render: " + image.absolutePath)
        }
        println("Offscreen UI audit renders: " + outputDirectory.absolutePath)
    }

    private fun renderPage(
        outputDirectory: File,
        fileName: String,
        viewport: Viewport,
        dark: Boolean,
        state: DesktopState,
        page: Int,
        written: MutableList<String>,
        prepare: ((ImageComposeScene, RenderSession) -> Unit)? = null,
    ) {
        capture(
            outputDirectory = outputDirectory,
            fileName = fileName,
            viewport = viewport,
            dark = dark,
            written = written,
            prepare = prepare,
        ) {
            AmdlDesktop(state, initialPage = page)
        }
    }

    private fun renderFrame(
        outputDirectory: File,
        fileName: String,
        viewport: Viewport,
        dark: Boolean,
        state: DesktopState,
        written: MutableList<String>,
        prepare: ((ImageComposeScene, RenderSession) -> Unit)? = null,
    ) {
        capture(
            outputDirectory = outputDirectory,
            fileName = fileName,
            viewport = viewport,
            dark = dark,
            written = written,
            prepare = prepare,
        ) {
            DesktopWindowFrame(
                captionBarHeight = 48.dp,
                captionBar = { DesktopCaptionBrand(Modifier.fillMaxSize()) },
                content = { AmdlDesktop(state, initialPage = 0) },
            )
        }
    }

    private fun captureOverlay(
        outputDirectory: File,
        fileName: String,
        viewport: Viewport,
        dark: Boolean,
        state: DesktopState,
        written: MutableList<String>,
        content: @Composable () -> Unit,
    ) {
        capture(
            outputDirectory = outputDirectory,
            fileName = fileName,
            viewport = viewport,
            dark = dark,
            written = written,
            prepare = { _, session -> session.pump(12) },
        ) {
            Scaffold {
                Box(Modifier.fillMaxSize()) {
                    AmdlDesktop(state, initialPage = 3)
                    content()
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun capture(
        outputDirectory: File,
        fileName: String,
        viewport: Viewport,
        dark: Boolean,
        written: MutableList<String>,
        prepare: ((ImageComposeScene, RenderSession) -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(
            width = viewport.pixelWidth,
            height = viewport.pixelHeight,
            density = Density(RenderDensity),
            layoutDirection = LayoutDirection.Ltr,
            content = {
                MiuixTheme(
                    colors = if (dark) darkColorScheme() else lightColorScheme(),
                ) {
                    content()
                }
            },
        )
        try {
            val session = RenderSession(scene)
            if (prepare != null) {
                session.pump()
            }
            prepare?.invoke(scene, session)

            val image = session.settle()
            try {
                check(image.width == viewport.pixelWidth && image.height == viewport.pixelHeight) {
                    "Unexpected image size " + image.width + "x" + image.height
                }
                val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG, 100)) {
                    "Skia failed to encode PNG"
                }
                try {
                    File(outputDirectory, fileName + ".png").writeBytes(data.bytes)
                    written += fileName + ".png"
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            scene.close()
        }
    }
}

private class RenderSession(private val scene: ImageComposeScene) {
    private var frameTimeNs = 0L

    fun settle(frames: Int = SettleFrames): Image {
        var image: Image? = null
        try {
            repeat(frames) {
                image?.close()
                image = scene.render(frameTimeNs)
                frameTimeNs += FrameStepNs
                Thread.sleep(FrameSleepMs)
            }
            return checkNotNull(image)
        } catch (failure: Throwable) {
            image?.close()
            throw failure
        }
    }

    fun pump(frames: Int = PrimerFrames) {
        settle(frames).close()
    }
}

private fun ImageComposeScene.visibleSemanticsNodes(
    widthPx: Int,
    heightPx: Int,
): List<SemanticsNode> = semanticsOwners
    .asSequence()
    .map { it.rootSemanticsNode }
    .flatMap { it.selfAndDescendants() }
    .filter { node ->
        val bounds = node.boundsInRoot
        bounds.width > 0f &&
            bounds.height > 0f &&
            bounds.right > 0f &&
            bounds.bottom > 0f &&
            bounds.left < widthPx &&
            bounds.top < heightPx
    }
    .toList()

private fun SemanticsNode.selfAndDescendants(): Sequence<SemanticsNode> = sequence {
    yield(this@selfAndDescendants)
    for (child in children) {
        yieldAll(child.selfAndDescendants())
    }
}

private fun SemanticsNode.hasText(predicate: (String) -> Boolean): Boolean {
    val text = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    val description = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    return (text + description).any(predicate)
}

private fun SemanticsNode.hasDescription(description: String): Boolean =
    config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(description)

private fun ImageComposeScene.requireRailDescription(
    description: String,
    viewport: Viewport,
) {
    checkNotNull(
        visibleSemanticsNodes(viewport.pixelWidth, viewport.pixelHeight)
            .firstOrNull { it.hasDescription(description) },
    ) { "Missing navigation rail toggle description: " + description }
}

private fun ImageComposeScene.requireSemanticTextLeft(
    text: String,
    viewport: Viewport,
): Float = checkNotNull(
    visibleSemanticsNodes(viewport.pixelWidth, viewport.pixelHeight)
        .firstOrNull { it.hasText { value -> value == text } },
) { "Missing semantics text: " + text }.boundsInRoot.left

private fun ImageComposeScene.requireSemanticsTag(
    tag: String,
    viewport: Viewport,
): SemanticsNode = checkNotNull(
    visibleSemanticsNodes(viewport.pixelWidth, viewport.pixelHeight)
        .firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag },
) { "Missing semantics tag: " + tag }

private fun ImageComposeScene.requireWideSettingsLayout(viewport: Viewport) {
    val expectedHeaderX = (ExpandedRailWidthDp + DividerThicknessDp + PageHorizontalPaddingDp) * RenderDensity
    val headerX = requireSemanticTextLeft(SettingsSubtitle, viewport)
    assertEquals(
        expectedHeaderX,
        headerX,
        LayoutTolerancePx,
        "Wide settings header must start after the 240dp rail, 0.75dp divider and 24dp page padding",
    )
    val scrollable = requireMainScrollable(viewport)
    assertEquals(
        expectedHeaderX,
        scrollable.boundsInRoot.left,
        LayoutTolerancePx,
        "Wide settings cards must align with the page header",
    )
    assertEquals(
        PageHorizontalPaddingDp * RenderDensity,
        viewport.pixelWidth - scrollable.boundsInRoot.right,
        LayoutTolerancePx,
        "Wide settings cards must use the 24dp page gutter instead of a centered content cap",
    )
}

private fun ImageComposeScene.requireSettingsBottom(viewport: Viewport) {
    val scrollable = requireMainScrollable(viewport)
    assertEquals(
        viewport.pixelHeight.toFloat(),
        scrollable.boundsInRoot.bottom,
        LayoutTolerancePx,
        "Settings scroll viewport must reach the content-area bottom; " +
            "bottom=" + scrollable.boundsInRoot.bottom + ", viewport=" + viewport.pixelHeight,
    )
    val scrollRange = checkNotNull(
        scrollable.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange),
    ) { "Missing settings vertical scroll range" }
    assertTrue(
        scrollRange.value() >= scrollRange.maxValue() - LayoutTolerancePx,
        "Settings must reach its maximum scroll position; value=" + scrollRange.value() +
            ", max=" + scrollRange.maxValue(),
    )

    val nodes = visibleSemanticsNodes(viewport.pixelWidth, viewport.pixelHeight)
    val footer = nodes.firstOrNull { node ->
        node.hasText { value -> value.startsWith(SettingsFooterPrefix) }
    } ?: error("Missing settings footer text starting with " + SettingsFooterPrefix)
    assertTrue(
        footer.boundsInRoot.top >= scrollable.boundsInRoot.top - LayoutTolerancePx &&
            footer.boundsInRoot.bottom <= viewport.pixelHeight + LayoutTolerancePx,
        "Settings footer must be fully visible after scrolling to the bottom; " +
            "bounds=" + footer.boundsInRoot,
    )
    val footerClearance = scrollable.boundsInRoot.bottom - footer.boundsInRoot.bottom
    assertTrue(
        footerClearance >= SettingsBottomClearanceDp * RenderDensity - LayoutTolerancePx,
        "Settings footer must keep its 24dp in-content bottom clearance; " +
            "clearance=" + footerClearance + "px",
    )

    val lastRow = nodes
        .filter { node -> node.hasText { value -> value == "下载引擎" } }
        .maxByOrNull { node -> node.boundsInRoot.top }
    checkNotNull(lastRow) { "Missing the last settings card row after scrolling to the bottom" }
    assertTrue(
        lastRow.boundsInRoot.top >= scrollable.boundsInRoot.top - LayoutTolerancePx &&
            lastRow.boundsInRoot.bottom <= footer.boundsInRoot.top + LayoutTolerancePx,
        "The last settings card row must be fully reachable above the footer; " +
            "bounds=" + lastRow.boundsInRoot,
    )
}

private fun ImageComposeScene.requireExpandedFrameLayout(viewport: Viewport) {
    val expectedHeaderX = (ExpandedRailWidthDp + DividerThicknessDp + PageHorizontalPaddingDp) * RenderDensity
    assertEquals(
        expectedHeaderX,
        requireSemanticTextLeft(HomeSubtitle, viewport),
        LayoutTolerancePx,
        "Expanded frame content must begin at 264.75dp",
    )
}

private fun ImageComposeScene.requireCollapsedFrameLayout(viewport: Viewport) {
    val expectedHeaderX = (CollapsedRailWidthDp + DividerThicknessDp + PageHorizontalPaddingDp) * RenderDensity
    assertEquals(
        expectedHeaderX,
        requireSemanticTextLeft(HomeSubtitle, viewport),
        LayoutTolerancePx,
        "Collapsed frame content must begin at 104.75dp",
    )
}

private fun ImageComposeScene.requireExpandedCaptionBrand(viewport: Viewport) {
    val divider = requireSemanticsTag("window-sidebar-divider", viewport)
    val brand = requireSemanticsTag(CaptionBrandAreaTag, viewport)
    val icon = requireSemanticsTag(CaptionBrandIconTag, viewport)
    val wordmark = checkNotNull(captionWordmark(viewport)) {
        "Missing expanded caption wordmark"
    }
    assertEquals(
        divider.boundsInRoot.left,
        brand.boundsInRoot.right,
        LayoutTolerancePx,
        "Caption brand must stop at the observed sidebar divider",
    )
    assertTrue(
        wordmark.boundsInRoot.left > icon.boundsInRoot.right,
        "Expanded caption wordmark must follow the app icon",
    )
    assertTrue(
        wordmark.boundsInRoot.right <= brand.boundsInRoot.right - 8f * RenderDensity,
        "Expanded caption needs comfortable space before the divider",
    )
    assertEquals(
        icon.boundsInRoot.center.y,
        wordmark.boundsInRoot.center.y,
        LayoutTolerancePx,
        "Expanded caption icon and wordmark must share a vertical center",
    )
}

private fun ImageComposeScene.requireCollapsedCaptionBrand(viewport: Viewport) {
    val divider = requireSemanticsTag("window-sidebar-divider", viewport)
    val brand = requireSemanticsTag(CaptionBrandAreaTag, viewport)
    val icon = requireSemanticsTag(CaptionBrandIconTag, viewport)
    assertEquals(
        divider.boundsInRoot.left,
        brand.boundsInRoot.right,
        LayoutTolerancePx,
        "Caption brand must stop at the observed sidebar divider",
    )
    assertEquals(
        brand.boundsInRoot.center.x,
        icon.boundsInRoot.center.x,
        LayoutTolerancePx,
        "Collapsed caption icon must be centered in the sidebar",
    )
    assertTrue(
        icon.boundsInRoot.right <= brand.boundsInRoot.right - 8f * RenderDensity,
        "Collapsed caption icon must keep space before the divider",
    )
    assertTrue(
        captionWordmark(viewport) == null,
        "Collapsed caption must hide the wordmark",
    )
}

private fun ImageComposeScene.captionWordmark(viewport: Viewport): SemanticsNode? =
    visibleSemanticsNodes(viewport.pixelWidth, viewport.pixelHeight)
        .firstOrNull { node -> node.hasText { value -> value == "AMDL" } }

private fun ImageComposeScene.requireWindowSidebarDivider(viewport: Viewport) {
    val marker = requireSemanticsTag("sidebar-divider-anchor", viewport)
    val divider = requireSemanticsTag("window-sidebar-divider", viewport)
    assertEquals(
        0f,
        divider.boundsInRoot.top,
        LayoutTolerancePx,
        "Window divider must begin at the top of the viewport",
    )
    assertEquals(
        viewport.pixelHeight.toFloat(),
        divider.boundsInRoot.bottom,
        LayoutTolerancePx,
        "Window divider must extend through the bottom of the viewport",
    )
    assertEquals(
        marker.boundsInRoot.left,
        divider.boundsInRoot.left,
        LayoutTolerancePx,
        "Window divider must track the sidebar marker",
    )
}

private fun ImageComposeScene.clickRailToggle(
    description: String,
    viewport: Viewport,
    session: RenderSession,
): Boolean {
    val descriptionNode = visibleSemanticsNodes(viewport.pixelWidth, viewport.pixelHeight)
        .firstOrNull { it.hasDescription(description) }
        ?: return false
    var clickable: SemanticsNode? = descriptionNode
    while (clickable != null) {
        val onClick = clickable.config.getOrNull(SemanticsActions.OnClick)
        if (onClick != null) {
            val action = onClick.action ?: return false
            check(action()) { "Navigation rail toggle click was rejected" }
            session.pump(RailToggleSettleFrames)
            return true
        }
        clickable = clickable.parent
    }
    return false
}

private fun ImageComposeScene.clickSemanticText(
    predicate: (String) -> Boolean,
    widthPx: Int,
    heightPx: Int,
    session: RenderSession,
): Boolean {
    val textNode = visibleSemanticsNodes(widthPx, heightPx)
        .firstOrNull { it.hasText(predicate) }
        ?: return false

    var clickable: SemanticsNode? = textNode
    while (clickable != null) {
        val onClick = clickable.config.getOrNull(SemanticsActions.OnClick)
        if (onClick != null) {
            val action = onClick.action ?: return false
            check(action()) { "Click action was rejected" }
            session.pump(6)
            return true
        }
        clickable = clickable.parent
    }

    // Semantics is the preferred route. This fallback exercises the real pointer pipeline
    // entirely inside the raster scene if a future component only exposes pointer handlers.
    val position = textNode.boundsInRoot.center
    sendPointerEvent(PointerEventType.Move, position)
    sendPointerEvent(PointerEventType.Press, position)
    session.pump(1)
    sendPointerEvent(PointerEventType.Release, position)
    session.pump(6)
    return true
}

private fun ImageComposeScene.scrollMainVertical(
    deltaY: Float,
    viewport: Viewport,
    session: RenderSession,
): Boolean {
    val scrollable = mainScrollableNode(viewport) ?: return false
    val scrollBy = scrollable.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return false
    scrollBy.invoke(0f, deltaY)
    session.pump(8)
    return true
}

private fun ImageComposeScene.mainScrollableNode(viewport: Viewport): SemanticsNode? =
    visibleSemanticsNodes(viewport.pixelWidth, viewport.pixelHeight)
        .filter { node ->
            node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                node.config.getOrNull(SemanticsActions.ScrollBy) != null
        }
        .maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }

private fun ImageComposeScene.requireMainScrollable(viewport: Viewport): SemanticsNode =
    checkNotNull(mainScrollableNode(viewport)) { "Missing main vertical scroll semantics" }

private fun ImageComposeScene.scrollToBottom(
    viewport: Viewport,
    session: RenderSession,
): Boolean {
    if (!scrollMainVertical(100_000f, viewport, session)) return false
    session.pump(4)
    return true
}

private fun ImageComposeScene.openLanguageMenu(
    viewport: Viewport,
    session: RenderSession,
): Boolean {
    if (!scrollToBottom(viewport, session)) return false
    if (
        clickSemanticText(
            predicate = { it == "歌曲信息语言" },
            widthPx = viewport.pixelWidth,
            heightPx = viewport.pixelHeight,
            session = session,
        )
    ) {
        return true
    }
    if (!scrollMainVertical(-260f, viewport, session)) return false
    return clickSemanticText(
        predicate = { it == "歌曲信息语言" },
        widthPx = viewport.pixelWidth,
        heightPx = viewport.pixelHeight,
        session = session,
    )
}

private fun DesktopState.cancelBackgroundWork() {
    val scopeField = javaClass.getDeclaredField("scope").apply { isAccessible = true }
    (scopeField.get(this) as CoroutineScope).cancel()
}

private fun DesktopState.applyShellState(
    config: UserConfig,
    loggedIn: Boolean,
    nativeReady: Boolean,
    nativeError: String?,
    loggingIn: Boolean = false,
    validating: Boolean = false,
    loginStatus: String = "",
    message: String? = null,
) {
    setMutableState("config", config)
    setMutableState("isLoggedIn", loggedIn)
    setMutableState("isValidatingSession", validating)
    setMutableState("loginStatus", loginStatus)
    setMutableState("isLoggingIn", loggingIn)
    setMutableState("message", message)
    setMutableState("nativeError", nativeError)
    setMutableState("nativeReady", nativeReady)
}

private fun DesktopState.applyDownloadState(
    tasks: List<Task> = emptyList(),
    failed: List<Task> = emptyList(),
    completed: List<Task> = emptyList(),
    parsing: Boolean = false,
    error: String? = null,
) {
    downloadManager.setFixtureFlow("mutableTasks", tasks)
    downloadManager.setFixtureFlow("mutableFailedTasks", failed)
    downloadManager.setFixtureFlow("mutableCompletedTasks", completed)
    downloadManager.setFixtureFlow("mutableIsParsing", parsing)
    downloadManager.setFixtureFlow("mutableError", error)
}

private fun DesktopState.applyNormalReadyState() {
    applyShellState(NormalConfig, loggedIn = true, nativeReady = true, nativeError = null)
    applyDownloadState()
}

private fun DesktopState.applyLoggedOutState() {
    applyShellState(NormalConfig, loggedIn = false, nativeReady = true, nativeError = null)
    applyDownloadState()
}

private fun DesktopState.applyLoadingState() {
    applyShellState(
        config = NormalConfig,
        loggedIn = false,
        nativeReady = false,
        nativeError = null,
        loggingIn = true,
        loginStatus = LongLoginStatus,
    )
    applyDownloadState(tasks = activeQueue().take(1), parsing = true)
}

private fun DesktopState.applyNativeErrorState() {
    applyShellState(
        config = LongConfig,
        loggedIn = true,
        nativeReady = false,
        nativeError = LongNativeError,
    )
    applyDownloadState()
}

private fun DesktopState.applyQueueFilledState() {
    applyShellState(NormalConfig, loggedIn = true, nativeReady = true, nativeError = null)
    applyDownloadState(tasks = activeQueue(), failed = failedQueue())
}

private fun DesktopState.applyHistoryFilledState() {
    applyShellState(NormalConfig, loggedIn = true, nativeReady = true, nativeError = null)
    applyDownloadState(completed = completedHistory())
}

private fun DesktopState.applySettingsState() {
    applyShellState(LongConfig, loggedIn = true, nativeReady = true, nativeError = null)
    applyDownloadState()
}

@Suppress("UNCHECKED_CAST")
private fun DesktopState.setMutableState(name: String, value: Any?) {
    val field = javaClass.getDeclaredField(name + "$" + "delegate").apply { isAccessible = true }
    (field.get(this) as MutableState<Any?>).value = value
}

@Suppress("UNCHECKED_CAST")
private fun <T> Any.setFixtureFlow(name: String, value: T) {
    val field = javaClass.getDeclaredField(name).apply { isAccessible = true }
    (field.get(this) as MutableStateFlow<T>).value = value
}

private fun createFolderFixtures(outputDirectory: File): List<FolderCase> {
    val root = File(outputDirectory, "_fixtures").apply {
        check(mkdirs() || isDirectory) { "Cannot create " + absolutePath }
    }
    val suffix = UUID.randomUUID().toString().take(8)
    val loaded = File(root, "loaded-" + suffix).apply {
        check(mkdirs()) { "Cannot create " + absolutePath }
    }
      listOf("Album Alpha", "独立单曲", "夜航", "用于检查换行的很长文件夹名称 — Apple Music Remastered and Live Concert Archive").forEach { name ->
        File(loaded, name).mkdirs()
    }

    val empty = File(root, "empty-" + suffix).apply {
        check(mkdirs()) { "Cannot create " + absolutePath }
    }
    val missing = File(root, "missing-" + suffix)

    val longSegment = "Long-directory-" + "abcdefghij".repeat(4)
    val chineseSegment = "第二层很长的文件夹名称-" + "甲乙丙丁".repeat(8)
    val longDirectory = File(File(File(root, "long-" + suffix), longSegment), chineseSegment)
    check(longDirectory.mkdirs()) { "Cannot create " + longDirectory.absolutePath }

    return listOf(
        // Exercise canonicalization with a clipboard-friendly separator and trailing slash.
        FolderCase("loaded-normalized", loaded.absolutePath.replace('\\', '/') + "/"),
        FolderCase("empty", empty.absolutePath),
        FolderCase("nonexistent", missing.absolutePath),
        FolderCase("long-name", longDirectory.absolutePath),
    )
}

private fun auditTask(
    id: String,
    title: String,
    artist: String,
    status: Task.Status,
    config: UserConfig = LongConfig,
): Task = Task(
    id = id,
    track = Track(
        storefront = "us",
        id = id,
        name = title,
        artistName = artist,
        artwork = null,
    ),
    config = config,
).apply {
    this.status = status
}

private fun activeQueue(): List<Task> = listOf(
    auditTask(
        id = "active-downloading",
        title = LongTrackName,
        artist = LongArtist,
        status = Task.Status.Downloading,
    ),
    auditTask(
        id = "active-pending",
        title = "A deliberately long song title that verifies two-line ellipsis without moving the trailing status",
        artist = "Various Artists / Original Motion Picture Soundtrack",
        status = Task.Status.Pending,
    ),
    auditTask(
        id = "active-failed",
        title = "失败的歌曲任务",
        artist = "Testing Artist",
        status = Task.Status.Failed(LongFailure),
    ),
)

private fun failedQueue(): List<Task> = listOf(
    auditTask(
        id = "failed-long",
        title = LongTrackName,
        artist = LongArtist,
        status = Task.Status.Failed(LongFailure),
    ),
    auditTask(
        id = "failed-network",
        title = "无法连接的下载任务",
        artist = "Another Artist",
        status = Task.Status.Failed(
            "网络连接意外中断，未能读取音频资源。请检查网络、系统代理和 Apple Music 连接后重试。" +
                "此错误文本故意较长，用于验证失败卡片底部信息的换行和间距。",
        ),
    ),
)

private fun completedHistory(): List<Task> = listOf(
    auditTask("completed-1", LongTrackName, LongArtist, Task.Status.Completed),
    auditTask("completed-2", "没有理想的人不伤心（现场版）", "新裤子乐队", Task.Status.Completed),
    auditTask("completed-3", "Across the Endless Blue Horizon", "Ensemble / Long Artist Name", Task.Status.Completed),
)

private data class Viewport(
    val widthDp: Int,
    val heightDp: Int,
) {
    val label = widthDp.toString() + "x" + heightDp.toString() + "dp"
    val pixelWidth = ceil(widthDp * RenderDensity).toInt()
    val pixelHeight = ceil(heightDp * RenderDensity).toInt()
}

private data class Page(val slug: String, val index: Int)
private data class ThemeSpec(val slug: String, val dark: Boolean)
private data class FolderCase(val slug: String, val path: String)

private const val RenderDensity = 1.25f
private const val FrameStepNs = 50_000_000L
private const val PrimerFrames = 8
private const val SettleFrames = 12
private const val FrameSleepMs = 8L
private const val RailToggleSettleFrames = 30
private const val RailWidthShiftDp = 160f
private const val StandardContentShiftDp = 160f
private const val ExpandedRailWidthDp = 240f
private const val CollapsedRailWidthDp = 80f
private const val DividerThicknessDp = 0.75f
private const val PageHorizontalPaddingDp = 24f
private const val LayoutTolerancePx = 1f
private const val RailWidthToleranceDp = 12f
private const val HomeSubtitle = "下载 Apple Music 歌曲与专辑"
private const val SettingsSubtitle = "下载偏好、存储与 Apple Music 账号"
private const val SettingsFooterPrefix = "AMDL for Windows"
private const val SettingsBottomClearanceDp = 24f
private const val ExpandRailDescription = "展开侧栏"
private const val CollapseRailDescription = "收起侧栏"
private const val ExpectedRenderCount = 40

private val NormalConfig = UserConfig(
    language = "zh-Hans-CN",
    preferAtmos = true,
    downloadPath = "C:" + File.separator + "Music" + File.separator + "AMDL",
    saveByAlbum = true,
    mergeSingles = true,
    saveTtml = true,
)

private val LongDownloadPath = "C:" + File.separator + "Users" + File.separator + "Public" +
    File.separator + "Music" + File.separator +
    "一个用于检查长路径换行、截断和中文排版的 Apple Music 下载目录" + File.separator +
    "Live Sessions and Remastered Deluxe Edition"

private val LongConfig = NormalConfig.copy(downloadPath = LongDownloadPath)

private val LongTrackName = "夜航星（Live at the End of the Universe / Remastered 2026）— 特别加长标题用于验证两行截断"
private val LongArtist = "HOYO-MiX · 谭维维 · The Long Named Orchestra & Chorus"
private val LongFailure = "无法写入目标文件：磁盘空间不足，同时目标目录不可写。请释放空间或重新选择下载目录后重试。" +
    "这是一段刻意加长的错误信息，用于检查失败卡片的换行、底部内边距和重试按钮对齐。"
private val LongNativeError = "下载引擎暂不可用：加载 AMDL 原生组件失败，找不到必需的解码入口。" +
    "请确认安装目录完整、Windows 安全软件没有隔离 DLL，然后重新启动应用。" +
    "此信息故意较长，用于检查首页与下载任务页的错误卡片。"
private val LongLoginStatus = "正在打开 Apple Music 登录窗口并等待浏览器返回授权结果…" +
    "如果浏览器没有自动出现，请检查默认浏览器、系统代理和弹出窗口设置。"

private val Viewports = listOf(
    Viewport(1180, 768),
    Viewport(940, 618),
)

private val Pages = listOf(
    Page("home", 0),
    Page("queue", 1),
    Page("history", 2),
    Page("settings", 3),
)

private val Themes = listOf(
    ThemeSpec("light", false),
    ThemeSpec("dark", true),
)
