package com.example.aplassistmvp0.guide

import org.junit.Assert.assertTrue
import org.junit.Test

class V3PlanClientTest {
    @Test fun instructionsFitTheSubscriptionBridgeLimit() {
        val length = V3PlanClient.INSTRUCTIONS.length
        assertTrue("The subscription bridge allows up to 8,000 instruction characters, received $length", length in 1..8_000)
    }
}
