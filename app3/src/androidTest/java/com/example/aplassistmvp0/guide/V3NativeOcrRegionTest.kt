package com.example.aplassistmvp0.guide

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.AssistAccessibilityService
import com.example.aplassistmvp0.accessibility.AccessibilityTreeParser
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.example.aplassistmvp0.state.AssistStateRepository
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.V3FormFixtureActivity
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** A public, offline probe. The color boundary is visual evidence and has no authority to activate a control. */
@RunWith(AndroidJUnit4::class)
class V3NativeOcrRegionTest {
    @Test fun aColoredControlCanBeLocalizedAndItsEarlierOcrResultExpiresAfterNativeScroll() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("qaV3LocalOcrRegion") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val fixture = instrumentation.context
        val target = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = target.contentResolver
        val oldServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        fun secure(block: () -> Unit) {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try { block() } finally { automation.dropShellPermissionIdentity() }
        }
        fun latestFrame(changed: (ScreenSnapshot) -> Boolean): ScreenSnapshot {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                val state = AssistStateRepository.state.value
                val screen = state.snapshot
                if (state.serviceConnected && state.foregroundPackage == fixture.packageName && screen != null &&
                    screen.packageName == fixture.packageName && !screen.truncated && changed(screen)) return screen
                SystemClock.sleep(80)
            }
            error("Public visual fixture did not publish a current, complete accessibility frame")
        }
        val recognizer = recognizerFor(fixture)
        try {
            instrumentation.runOnMainSync { GuideController.initialize(target); GuideController.stop() }
            val service = ComponentName(target, AssistAccessibilityService::class.java).flattenToString()
            secure {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, service)
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            }
            fixture.startActivity(Intent(fixture, V3FormFixtureActivity::class.java)
                .putExtra("ocrRegionProbe", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val opening = latestFrame { it.allNodes.any { node -> node.contentDescription == "공개 조작 영역 목록" && node.scrollable } }
            automation.waitForIdle(350, 5_000)
            instrumentation.runOnMainSync { AssistAccessibilityService.requestRefresh() }
            val before = latestFrame { it.snapshotId > opening.snapshotId }
            assertTrue(before.allNodes.none { it.text in setOf("타이머", "스톱워치", "도움말") || it.clickable })
            val capture = runBlocking { AssistAccessibilityService.captureVision(before) }
            val rejection = GuideTrace.events.value.lastOrNull { it.stage == "vision_capture_unavailable" }?.fields?.get("reason")
            assertNotNull("The current frame should support local window capture: $rejection", capture)

            val root = requireNotNull(automation.rootInActiveWindow)
            val scroll = root.findAccessibilityNodeInfosByText("공개 조작 영역 목록").single { it.isScrollable }
            val viewport = Rect().also(scroll::getBoundsInScreen)
            val oldBitmap = requireNotNull(automation.takeScreenshot())
            try {
                val oldOcr = recognizer.process(InputImage.fromBitmap(oldBitmap, 0))
                automation.executeAndWaitForEvent({
                    assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                }, { event ->
                    event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && event.windowId == before.windowId &&
                        event.packageName?.toString() == fixture.packageName
                }, 4_000)
                automation.waitForIdle(250, 4_000)
                val after = latestFrame { it.snapshotId > before.snapshotId && it.visualInteractionGeneration > before.visualInteractionGeneration }
                val oldWords = Tasks.await(oldOcr, 12, TimeUnit.SECONDS)
                val oldText = requireNotNull(textBounds(oldWords, "타이머"))
                val oldPanel = requireNotNull(panelBounds(oldBitmap, oldText, viewport))
                assertNull(textBounds(oldWords, "스톱워치"))
                assertEquals(before.windowId, after.windowId)
                assertEquals(before.sourceEpoch, after.sourceEpoch)
                assertFalse(GuideVisionPolicy.sameScreen(before, after))
                assertNull("The earlier frame cannot start another visual capture", runBlocking { AssistAccessibilityService.captureVision(before) })

                val newBitmap = requireNotNull(automation.takeScreenshot())
                try {
                    val newWords = Tasks.await(recognizer.process(InputImage.fromBitmap(newBitmap, 0)), 12, TimeUnit.SECONDS)
                    val newText = requireNotNull(textBounds(newWords, "스톱워치"))
                    val newPanel = requireNotNull(panelBounds(newBitmap, newText, viewport)) { "No solid region around $newText within $viewport" }
                    val flatText = requireNotNull(textBounds(newWords, "도움말"))
                    assertNull("Text without a separate enclosing surface should give no region", panelBounds(newBitmap, flatText, viewport))
                    assertNull(textBounds(newWords, "타이머"))
                    assertFalse(Rect.intersects(oldPanel, newPanel))
                    assertTrue(after.allNodes.none { it.text in setOf("타이머", "스톱워치", "도움말") || it.clickable })
                    val density = fixture.resources.displayMetrics.density
                    val margin = (16 * density).roundToInt()
                    val verticalMargin = (8 * density).roundToInt()
                    val height = (64 * density).roundToInt()
                    val flatRowHeight = (48 * density).roundToInt()
                    for (panel in listOf(oldPanel, newPanel)) {
                        assertEquals(viewport.left + margin, panel.left)
                        assertEquals(viewport.right - margin, panel.right)
                        assertEquals(height, panel.height())
                    }
                    assertEquals(viewport.top + verticalMargin, oldPanel.top)
                    assertEquals(viewport.bottom - flatRowHeight - verticalMargin, newPanel.bottom)
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("v3VisualRegionProof", JSONObject().put("oldText", oldText.toShortString())
                            .put("newText", newText.toShortString()).put("oldPanel", oldPanel.toShortString())
                            .put("newPanel", newPanel.toShortString()).put("viewport", viewport.toShortString())
                            .put("newInteractionGeneration", after.visualInteractionGeneration > before.visualInteractionGeneration)
                            .put("staleImageRejected", true).put("flatLabelRejected", true)
                            .put("guideNodeAvailableForDetectedPanel", false).toString())
                    })
                } finally { newBitmap.recycle() }
            } finally { oldBitmap.recycle() }
        } finally {
            recognizer.close()
            secure {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled)
            }
        }
    }

    @Test fun duplicateOcrLabelsAndIdenticalIconsStillNeedCurrentActionEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("qaV3LocalOcrDuplicate") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val fixture = instrumentation.context
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = (flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) and
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        }
        fixture.startActivity(Intent(fixture, V3FormFixtureActivity::class.java)
            .putExtra("ocrDuplicateProbe", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

        val parser = AccessibilityTreeParser()
        val deadline = SystemClock.uptimeMillis() + 8_000
        var observed: ScreenSnapshot? = null
        while (SystemClock.uptimeMillis() < deadline) {
            if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == fixture.packageName) {
                val tree = parser.parse(root)
                val bounds = Rect().also(root::getBoundsInScreen)
                if (!tree.truncated && tree.allNodes.count { it.text == "열기" && it.clickable } == 2 &&
                    tree.allNodes.any { it.contentDescription == "사진 목록" && it.clickable } &&
                    tree.allNodes.any { it.contentDescription == "장식 그림" && !it.clickable }) {
                    observed = ScreenSnapshot(1, fixture.packageName, root.windowId, System.currentTimeMillis(),
                        tree.nodes, tree.allNodes, false, NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
                    break
                }
            }
            SystemClock.sleep(80)
        }
        val frame = requireNotNull(observed) { "Duplicate labels and icon were not present in the native accessibility tree" }
        automation.waitForIdle(350, 5_000)
        val recognizer = recognizerFor(fixture)
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            val words = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 12, TimeUnit.SECONDS)
                .textBlocks.flatMap { it.lines }
            val viewport = requireNotNull(frame.viewport)
            val search = Rect(viewport.left, viewport.top, viewport.right, viewport.bottom)
            val duplicates = words.filter { it.text.filterNot(Char::isWhitespace) == "열기" }
            assertEquals(2, duplicates.size)
            val panels = duplicates.map { word -> requireNotNull(panelBounds(bitmap, requireNotNull(word.boundingBox), search)) }
            assertFalse(Rect.intersects(panels[0], panels[1]))
            fun controlAt(rect: Rect) = frame.allNodes.singleOrNull { node ->
                node.clickable && node.enabled && node.visibleToUser && node.bounds == NodeBounds(rect.left, rect.top, rect.right, rect.bottom) &&
                    GuideTouchPolicy.exposedBounds(frame, node, viewport) != null
            }
            val matched = panels.map { requireNotNull(controlAt(it)) }
            assertEquals(2, matched.map { it.id }.distinct().size)
            assertTrue(matched.all { it.text == "열기" && GuideVisionPolicy.resolveHighlight(frame, frame, it.id) == null })

            val icon = frame.allNodes.single { it.contentDescription == "사진 목록" && it.clickable }
            val iconBounds = icon.bounds.let { Rect(it.left, it.top, it.right, it.bottom) }
            val decor = frame.allNodes.single { it.contentDescription == "장식 그림" && !it.clickable }
            val decorBounds = decor.bounds.let { Rect(it.left, it.top, it.right, it.bottom) }
            assertTrue(search.contains(decorBounds))
            assertTrue(words.none { it.boundingBox?.let { box -> Rect.intersects(box, iconBounds) || Rect.intersects(box, decorBounds) } == true })
            val iconPixels = pixels(bitmap, iconBounds)
            assertTrue(iconPixels.any { it != iconPixels.first() })
            assertArrayEquals(iconPixels, pixels(bitmap, decorBounds))
            assertSame(icon, controlAt(iconBounds))
            assertNull(controlAt(decorBounds))
            assertSame(icon, GuideVisionPolicy.resolveHighlight(frame, frame, icon.id))
            assertNull(GuideVisionPolicy.resolveHighlight(frame, frame, decor.id))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("v3VisualAmbiguityProof", JSONObject().put("duplicateLabels", duplicates.size)
                    .put("distinctGeometricBindings", matched.size).put("duplicateGuidanceApproved", 0)
                    .put("iconBounds", iconBounds.toShortString()).put("decorBounds", decorBounds.toShortString())
                    .put("iconsPixelIdentical", true).put("iconOcrLines", 0)
                    .put("iconNativeActionVerified", true).put("decorativeNodeFound", true).put("decorativeActionFound", false).toString())
            })
        } finally {
            bitmap.recycle()
            recognizer.close()
        }
    }

    private fun recognizerFor(context: Context): TextRecognizer {
        MlKit.initialize(object : ContextWrapper(context) {
            override fun getApplicationContext() = this
        })
        return TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private fun pixels(bitmap: Bitmap, bounds: Rect): IntArray = IntArray(bounds.width() * bounds.height()).also {
        bitmap.getPixels(it, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height())
    }

    private fun textBounds(result: Text, label: String): Rect? = result.textBlocks.flatMap { it.lines }
        .singleOrNull { it.text.filterNot(Char::isWhitespace) == label }?.boundingBox

    /** Expands from OCR-adjacent pixels and checks for an independently bounded solid rectangle. */
    private fun panelBounds(bitmap: Bitmap, text: Rect, viewport: Rect): Rect? {
        val padding = (text.height() / 4).coerceAtLeast(2)
        val seed = Rect(text).apply { inset(-padding, -padding) }
        if (!viewport.contains(seed)) return null
        val x = seed.left
        val y = seed.top
        var left = seed.left
        var right = seed.right - 1
        var top = seed.top
        var bottom = seed.bottom - 1
        val fill = bitmap.getPixel(left, y)
        if (listOf(bitmap.getPixel(right, y), bitmap.getPixel(x, bottom), bitmap.getPixel(right, bottom)).any { it != fill }) return null
        while (left > viewport.left && bitmap.getPixel(left, y) == fill) left--
        while (right < viewport.right - 1 && bitmap.getPixel(right, y) == fill) right++
        while (top > viewport.top && bitmap.getPixel(x, top) == fill) top--
        while (bottom < viewport.bottom - 1 && bitmap.getPixel(x, bottom) == fill) bottom++
        if (left == viewport.left || right == viewport.right - 1 || top == viewport.top || bottom == viewport.bottom - 1) return null
        val panel = Rect(left + 1, top + 1, right, bottom)
        if (!panel.contains(seed)) return null
        fun edge(inside: Int, outside: Int) = inside == fill && outside != fill
        for (quarter in 1..3) {
            val across = panel.left + panel.width() * quarter / 4
            val down = panel.top + panel.height() * quarter / 4
            if (!edge(bitmap.getPixel(across, panel.top), bitmap.getPixel(across, top)) ||
                !edge(bitmap.getPixel(across, panel.bottom - 1), bitmap.getPixel(across, bottom)) ||
                !edge(bitmap.getPixel(panel.left, down), bitmap.getPixel(left, down)) ||
                !edge(bitmap.getPixel(panel.right - 1, down), bitmap.getPixel(right, down))) return null
        }
        return panel
    }
}
