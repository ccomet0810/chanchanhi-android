package com.example.aplassistmvp0.update

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdatePolicyTest {
    private fun choose(available: Boolean = true, priority: Int = 0, flexible: Boolean = true,
                       immediate: Boolean = true, declined: Boolean = false) =
        AppUpdatePolicy.choose(available, priority, flexible, immediate, declined)

    @Test fun noUpdateMeansNoPrompt() {
        assertEquals(AppUpdateMode.NONE, choose(available = false, priority = 5))
    }

    @Test fun ordinaryReleasesUseFlexibleUpdates() {
        assertEquals(AppUpdateMode.FLEXIBLE, choose(priority = 0))
        assertEquals(AppUpdateMode.FLEXIBLE, choose(priority = 3))
    }

    @Test fun highPriorityReleasesAreImmediate() {
        assertEquals(AppUpdateMode.IMMEDIATE, choose(priority = 4))
        assertEquals(AppUpdateMode.IMMEDIATE, choose(priority = 5, declined = true))
    }

    @Test fun highPriorityFallsBackToFlexibleWhenImmediateIsNotAllowed() {
        assertEquals(AppUpdateMode.FLEXIBLE, choose(priority = 5, immediate = false))
    }

    @Test fun declinedFlexibleUpdateIsNotAskedAgain() {
        assertEquals(AppUpdateMode.NONE, choose(priority = 1, declined = true))
        assertEquals(AppUpdateMode.NONE, choose(flexible = false, immediate = false))
    }
}
