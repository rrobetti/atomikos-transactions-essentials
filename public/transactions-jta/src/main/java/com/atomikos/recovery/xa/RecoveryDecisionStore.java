/**
 * Copyright (C) 2000-2026 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.recovery.xa;

public interface RecoveryDecisionStore {

	RecoveryDecision getOrRecord(String coordinatorId, RecoveryDecision proposedDecision);

}
