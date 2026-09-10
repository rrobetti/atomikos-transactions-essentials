/**
 * Copyright (C) 2000-2026 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.recovery.xa;

public class RecoveryDecisionArbiter {

	private final RecoveryDecisionStore recoveryDecisionStore;

	public RecoveryDecisionArbiter(RecoveryDecisionStore recoveryDecisionStore) {
		if (recoveryDecisionStore == null) {
			throw new IllegalArgumentException("Missing required recoveryDecisionStore");
		}
		this.recoveryDecisionStore = recoveryDecisionStore;
	}

	public RecoveryDecision decide(String coordinatorId, RecoveryDecision proposedDecision) {
		return recoveryDecisionStore.getOrRecord(coordinatorId, proposedDecision);
	}

}
