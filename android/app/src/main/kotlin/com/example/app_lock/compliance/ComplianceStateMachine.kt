package com.example.app_lock.compliance

enum class ComplianceEvent {
    STOP,
    LOCATION_DISABLED_TRANSIENT,
    START_GRACE_COUNTDOWN,
    GRACE_TIMEOUT,
    LOCK_TIMEOUT,
    SCREEN_DISMISSED,
    USER_UNLOCKED_LOCATION_ON,
    USER_UNLOCKED_LOCATION_OFF,
    LOCATION_RESTORED_TRANSIENT,
    RESET_TO_NORMAL
}

object ComplianceStateMachine {
    fun transition(currentState: LocationComplianceState, event: ComplianceEvent): LocationComplianceState {
        return when (event) {
            ComplianceEvent.STOP -> LocationComplianceState.NORMAL
            ComplianceEvent.LOCATION_DISABLED_TRANSIENT -> LocationComplianceState.LOCATION_OFF
            ComplianceEvent.START_GRACE_COUNTDOWN -> LocationComplianceState.COUNTDOWN_RUNNING
            ComplianceEvent.GRACE_TIMEOUT -> {
                if (currentState == LocationComplianceState.COUNTDOWN_RUNNING) {
                    LocationComplianceState.COMPLIANCE_SCREEN
                } else {
                    currentState
                }
            }
            ComplianceEvent.LOCK_TIMEOUT -> {
                if (currentState == LocationComplianceState.COMPLIANCE_SCREEN) {
                    LocationComplianceState.LOCKED
                } else {
                    currentState
                }
            }
            ComplianceEvent.SCREEN_DISMISSED -> {
                if (currentState == LocationComplianceState.COMPLIANCE_SCREEN) {
                    LocationComplianceState.LOCKED
                } else {
                    currentState
                }
            }
            ComplianceEvent.USER_UNLOCKED_LOCATION_ON -> {
                if (currentState == LocationComplianceState.LOCKED) {
                    LocationComplianceState.NORMAL
                } else {
                    currentState
                }
            }
            ComplianceEvent.USER_UNLOCKED_LOCATION_OFF -> {
                if (currentState == LocationComplianceState.LOCKED) {
                    LocationComplianceState.LOCATION_OFF
                } else {
                    currentState
                }
            }
            ComplianceEvent.LOCATION_RESTORED_TRANSIENT -> LocationComplianceState.LOCATION_RESTORED
            ComplianceEvent.RESET_TO_NORMAL -> LocationComplianceState.NORMAL
        }
    }
}
