package com.example.aplassistmvp0.guide

/** Migrate the old subscription default once; an explicit later choice remains explicit. */
internal object GuideModelSelectionPolicy {
    fun resolve(saved: String?, subscriptionInitialized: Boolean, terraMigrated: Boolean): GuideBackend {
        if (!subscriptionInitialized) return GuideBackend.defaultSubscription
        val selected = GuideBackend.entries.firstOrNull { it.name == saved } ?: return GuideBackend.defaultSubscription
        return if (!terraMigrated && selected == GuideBackend.SUBSCRIPTION_SOL) GuideBackend.defaultSubscription else selected
    }
}
