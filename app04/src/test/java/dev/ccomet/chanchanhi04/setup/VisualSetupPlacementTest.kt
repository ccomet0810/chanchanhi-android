package dev.ccomet.chanchanhi04.setup

import org.junit.Assert.*
import org.junit.Test

class VisualSetupPlacementTest {
    @Test fun bottomPermissionButtonMovesTheGuideAboveIt() {
        val p=VisualSetupAvoidance.place(48,920,340,580,800,880,12)
        assertTrue(p.avoidsTarget);assertTrue(p.top+p.height<=788)
    }
    @Test fun aMiddleTargetUsesAScrollableBandWhenNeitherEdgeFitsTheWholePanel() {
        val p=VisualSetupAvoidance.place(48,920,600,280,400,470,12)
        assertTrue(p.avoidsTarget);assertTrue(p.height<600)
        assertTrue(p.top+p.height<=388||p.top>=482)
    }
    @Test fun keyboardAndSystemInsetsClampTheEntirePanel() {
        val p=VisualSetupAvoidance.place(80,540,700,700)
        assertEquals(80,p.top);assertEquals(460,p.height)
    }
    @Test fun aUserPositionIsPreservedWhileItIsSafe() {
        val p=VisualSetupAvoidance.place(48,920,200,360,800,880,12)
        assertEquals(360,p.top);assertEquals(200,p.height)
    }
    @Test fun targetsThatFillTheAvailableScreenAreReportedAsLimited() {
        assertFalse(VisualSetupAvoidance.place(48,920,200,48,0,1000,12).avoidsTarget)
    }
    @Test fun placementAlwaysFitsTheSafeAreaAndAvoidsATargetWhenItClaimsTo() {
        for(height in listOf(1,64,200,900))for(top in -100..1000 step 50)for(bottom in top+1..1100 step 137) {
            val p=VisualSetupAvoidance.place(48,920,height,700,top,bottom,12)
            assertTrue(p.height>0);assertTrue(p.top>=48);assertTrue(p.top+p.height<=920)
            if(p.avoidsTarget)assertTrue(p.top+p.height<=top-12||p.top>=bottom+12||bottom<=48||top>=920)
        }
    }
}
