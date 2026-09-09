/**
 * Copyright (C) 2000-2026 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.recovery.xa;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory recovery decisions are kept for the lifetime of the recovery manager
 * instance to avoid forgetting a sibling branch decision while late branches can
 * still appear.
 */
public class InMemoryRecoveryDecisionStore implements RecoveryDecisionStore {

	private final ConcurrentMap<String, RecoveryDecision> recoveryDecisions = new ConcurrentHashMap<String, RecoveryDecision>();

	@Override
	public RecoveryDecision getOrRecord(String coordinatorId, RecoveryDecision proposedDecision) {
		return recoveryDecisions.computeIfAbsent(coordinatorId, ignored -> proposedDecision);
	}

}
