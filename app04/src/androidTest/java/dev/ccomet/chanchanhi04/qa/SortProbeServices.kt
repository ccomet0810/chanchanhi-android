package dev.ccomet.chanchanhi04.qa

/** Display labels only. No test ever enables these services or grants access. */
open class SortProbeService:android.accessibilityservice.AccessibilityService(){
    override fun onAccessibilityEvent(event:android.view.accessibility.AccessibilityEvent?){}
    override fun onInterrupt(){}
}
class SortBangService:SortProbeService()
class SortNumberService:SortProbeService()
class SortStarService:SortProbeService()
class SortEmojiService:SortProbeService()
