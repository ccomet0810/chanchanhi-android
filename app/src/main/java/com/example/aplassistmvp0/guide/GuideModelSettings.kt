package com.example.aplassistmvp0.guide

import android.content.Context

class GuideModelSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("guide-model", Context.MODE_PRIVATE)
    fun current(): GuideBackend {
        val initialized = prefs.getBoolean("subscription-default-v1", false)
        val migrated = prefs.getBoolean("subscription-terra-default-v1", false)
        val selected = GuideModelSelectionPolicy.resolve(prefs.getString("backend", null), initialized, migrated)
        if (!migrated) save(selected)
        return selected
    }
    fun save(backend: GuideBackend) {
        check(prefs.edit().putString("backend", backend.name).putBoolean("subscription-default-v1", true)
            .putBoolean("subscription-terra-default-v1", true).commit())
    }
}
