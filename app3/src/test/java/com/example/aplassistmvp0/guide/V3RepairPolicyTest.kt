package com.example.aplassistmvp0.guide

import org.junit.Assert.*
import org.junit.Test

class V3RepairPolicyTest {
    @Test fun onlyA502CompletedResultContractFailureIsRepairable(){
        for((status,code,kind) in listOf(Triple(502,"invalid_model_result",V3PlanFailureKind.INVALID_RESPONSE),
            Triple(401,"invalid_model_result",V3PlanFailureKind.AUTHENTICATION),Triple(403,"invalid_model_result",V3PlanFailureKind.AUTHENTICATION),
            Triple(429,"invalid_model_result",V3PlanFailureKind.LIMIT),Triple(429,"guide_busy",V3PlanFailureKind.BUSY),
            Triple(429,"guide_rate_limit",V3PlanFailureKind.BUSY),
            Triple(502,"missing_completed",V3PlanFailureKind.UNKNOWN_REMOTE_RESULT),Triple(502,"invalid_model_usage",V3PlanFailureKind.UNKNOWN_REMOTE_RESULT),
            Triple(400,"invalid_model_request",V3PlanFailureKind.PROTOCOL_MISMATCH),Triple(503,"invalid_model_result",V3PlanFailureKind.REMOTE))){
            val f=V3FailureClassifier.fromBridge(BridgeRequestFailure(status,code,"표시 문구"))
            assertEquals("$status/$code",kind,f.kind);assertEquals(kind==V3PlanFailureKind.INVALID_RESPONSE,f.diagnostic!=null)
        }
    }
    @Test fun ordinaryTransportExceptionsNeverGrantAPlanningRetry(){
        val f=V3FailureClassifier.fromBridge(IllegalStateException("연결 확인"))
        assertEquals(V3PlanFailureKind.REMOTE,f.kind);assertNull(f.diagnostic)
    }
    @Test fun resourceAndObservationProblemsCannotBeRepairedByWeakeningThePlan(){
        for(code in listOf("condition_history_limit","missing_goal"))assertFalse(V3RepairPolicy.allows(0,V3PlanDiagnostic(V3DiagnosticSource.SESSION,code),false))
        for(code in listOf("preparation_history_limit","ambiguous_preparation_binding","missing_owned_preparation","incomplete_preparation_observation"))
            assertFalse(V3RepairPolicy.allows(0,V3PlanDiagnostic(V3DiagnosticSource.PREPARATION,code),false))
    }
    @Test fun correctionIsLimitedAndNeverUsedForAnUnresolvedOperation(){
        val d=V3PlanDiagnostic(V3DiagnosticSource.VALIDATION,"unknown_relation_member")
        assertTrue(V3RepairPolicy.allows(0,d,false));assertFalse(V3RepairPolicy.allows(1,d,false));assertFalse(V3RepairPolicy.allows(0,d,true))
    }
    @Test fun arbitraryExceptionTextCannotBecomeAValidatorCode(){
        assertThrows(IllegalArgumentException::class.java){V3PlanDiagnostic(V3DiagnosticSource.RESPONSE,"raw response or user@example.invalid")}
    }
}
