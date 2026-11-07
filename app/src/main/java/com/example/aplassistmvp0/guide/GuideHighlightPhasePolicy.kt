package com.example.aplassistmvp0.guide

internal object GuideHighlightPhasePolicy {
    fun allowed(phase: String, openingActive: Boolean, visual: Boolean): Boolean =
        phase in setOf("thinking", "waiting_screen", "guiding") ||
            (phase == "opening_app" && openingActive && !visual)
}
