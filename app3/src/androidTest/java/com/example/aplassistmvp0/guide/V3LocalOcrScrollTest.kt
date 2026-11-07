package com.example.aplassistmvp0.guide

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.AccessibilityTreeParser
import com.example.aplassistmvp0.accessibility.ParsedTree
import com.example.aplassistmvp0.v3.V3FormFixtureActivity
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public fixture only. The production guide and its network client are outside this probe. */
@RunWith(AndroidJUnit4::class)
class V3LocalOcrScrollTest {
    @Test fun koreanTextBecomesVisibleAfterOneNativeScrollWhileTheTreeRemainsPartial() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("qaV3LocalOcr") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.context
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = (flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) and
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        }
        val intent = Intent(context, V3FormFixtureActivity::class.java)
            .putExtra("ocrScrollProbe", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        val beforeFrame = awaitFrame(automation, context.packageName, "공개 첫 행")
        val beforeRoot = beforeFrame.root
        val list = beforeRoot.findAccessibilityNodeInfosByText("공개 세로 목록").single { it.isScrollable }
        val listBounds = Rect().also(list::getBoundsInScreen)
        val beforeRow = beforeFrame.row
        assertPartialTree(beforeFrame.tree)

        val initStarted = SystemClock.elapsedRealtimeNanos()
        // Keep asset and component lookup in the test APK, whose instrumentation context has no Application.
        MlKit.initialize(object : ContextWrapper(context) {
            override fun getApplicationContext() = this
        })
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val initializedMs = elapsedMs(initStarted)
            val before = read(automation, recognizer)
            assertTrue(beforeRow.contains(requireNotNull(before.words["타이머"])))
            assertFalse(before.words.containsKey("스톱워치"))
            val repeated = List(5) { read(automation, recognizer) }
            assertTrue(repeated.all { beforeRow.contains(requireNotNull(it.words["타이머"])) })

            val scrollStarted = SystemClock.elapsedRealtimeNanos()
            val event = automation.executeAndWaitForEvent({
                assertTrue(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            }, { event ->
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                    event.packageName?.toString() == context.packageName && event.windowId == beforeRoot.windowId
            }, 4_000)
            assertNotNull(event)
            val scrollEventMs = elapsedMs(scrollStarted)
            automation.waitForIdle(250, 4_000)
            val afterFrame = awaitFrame(automation, context.packageName, "공개 다음 행")
            val afterRow = afterFrame.row
            assertEquals(beforeRoot.windowId, afterFrame.root.windowId)
            assertTrue(listBounds.contains(afterRow))
            assertFalse(Rect.intersects(beforeRow, afterRow))
            assertPartialTree(afterFrame.tree)

            val after = read(automation, recognizer)
            val targetBox = requireNotNull(after.words["스톱워치"])
            assertTrue(afterRow.contains(targetBox))
            assertTrue(targetBox.width() < afterRow.width() && targetBox.height() < afterRow.height())
            assertFalse(after.words.containsKey("타이머"))
            val scrollToOcrMs = elapsedMs(scrollStarted)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("v3LocalOcrScrollProof", JSONObject()
                    .put("initializationMs", initializedMs).put("firstCaptureMs", before.captureMs)
                    .put("firstRecognitionMs", before.recognitionMs).put("repeatRecognitionMs", JSONArray(repeated.map { it.recognitionMs }))
                    .put("repeatCaptureMs", JSONArray(repeated.map { it.captureMs }))
                    .put("scrollEventMs", scrollEventMs).put("scrollToOcrMs", scrollToOcrMs)
                    .put("afterScrollRecognitionMs", after.recognitionMs)
                    .put("targetTextBounds", targetBox.toShortString()).put("targetRowBounds", afterRow.toShortString())
                    .put("beforeRowBounds", beforeRow.toShortString()).put("listBounds", listBounds.toShortString())
                    .put("missingChildrenBeforeAndAfter", 1).put("nativeScrolls", 1).toString())
            })
        } finally {
            recognizer.close()
        }
    }

    private data class TreeFrame(val root: AccessibilityNodeInfo, val row: Rect, val tree: ParsedTree)

    private fun awaitFrame(automation: UiAutomation, packageName: String, visibleRow: String): TreeFrame {
        val deadline = SystemClock.uptimeMillis() + 8_000
        val parser = AccessibilityTreeParser()
        while (SystemClock.uptimeMillis() < deadline) {
            if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == packageName) {
                val tree = parser.parse(root)
                val row = tree.allNodes.singleOrNull { it.visibleToUser && it.contentDescription == visibleRow }
                if (row != null) {
                    val bounds = row.bounds
                    return TreeFrame(root, Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), tree)
                }
            }
            SystemClock.sleep(80)
        }
        error("Public OCR fixture row did not become visible: $visibleRow")
    }

    private fun assertPartialTree(tree: ParsedTree) {
        assertTrue(tree.truncated)
        assertEquals(1, tree.readGaps.missingChildren)
        assertFalse(tree.allNodes.any { it.text in setOf("타이머", "스톱워치") })
    }

    private data class OcrSample(val captureMs: Long, val recognitionMs: Long, val words: Map<String, Rect>)

    private fun read(automation: UiAutomation, recognizer: TextRecognizer): OcrSample {
        val captureStarted = SystemClock.elapsedRealtimeNanos()
        val bitmap = requireNotNull(automation.takeScreenshot())
        val captureMs = elapsedMs(captureStarted)
        try {
            val started = SystemClock.elapsedRealtimeNanos()
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 12, TimeUnit.SECONDS)
            val recognitionMs = elapsedMs(started)
            val words = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                line.boundingBox?.let { line.text.replace(" ", "") to it }
            }.toMap()
            return OcrSample(captureMs, recognitionMs, words)
        } finally {
            bitmap.recycle()
        }
    }

    private fun elapsedMs(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
}
