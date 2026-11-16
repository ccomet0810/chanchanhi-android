package dev.ccomet.chanchanhi04.admin

import org.junit.Assert.*
import org.junit.Test

class ReviewAdminPolicyTest {
    private fun allowed(enrolled:Boolean=false,label:String="",saved:Boolean=false,hash:String?=null,participant:Boolean=false,logs:Boolean=false)=
        ReviewAdminPolicy.allows(enrolled,label,saved,hash,participant,logs)
    @Test fun freshEmptyInstallationCanBePrepared(){assertTrue(allowed())}
    @Test fun existingParticipantAndGuidanceRecordsDenyFreshSetup(){assertFalse(allowed(participant=true));assertFalse(allowed(logs=true))}
    @Test fun anyOrdinaryEnrollmentDeniesTestPinEvenWithTheTestConnection(){
        for(label in listOf("ordinary participant","[test] public QA",""))assertFalse(allowed(true,label,true,ReviewAdminPolicy.BRIDGE_KEY_HASH))
    }
    @Test fun ordinaryMissingOrUnknownBridgeScopeFailsClosed(){
        for(hash in listOf(null,"ordinary-private-connection","")){
            assertFalse(allowed(saved=true,hash=hash))
            assertFalse(allowed(true,ReviewAdminPolicy.DEVICE_LABEL,true,hash))
        }
    }
    @Test fun authorizedTestDeviceCanUseOnlyTheSeparateTestBridge(){
        assertTrue(allowed(true,ReviewAdminPolicy.DEVICE_LABEL,participant=true,logs=true))
        assertTrue(allowed(true,ReviewAdminPolicy.DEVICE_LABEL,true,ReviewAdminPolicy.BRIDGE_KEY_HASH,true,true))
        assertFalse(allowed(true,ReviewAdminPolicy.DEVICE_LABEL,true,"operator-connection",true,true))
    }
    @Test fun emptyDeviceWithTheSeparateBridgeCanFinishItsEnrollment(){assertTrue(allowed(saved=true,hash=ReviewAdminPolicy.BRIDGE_KEY_HASH))}
}
