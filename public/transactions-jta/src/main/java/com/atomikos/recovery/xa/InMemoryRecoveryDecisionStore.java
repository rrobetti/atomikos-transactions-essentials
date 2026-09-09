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
 * <p>
 * This means the map can grow with the number of recovered coordinators seen by
 * the manager instance. That tradeoff is intentional for the open-source
 * default: without a provably safe cleanup hook in the current recovery flow,
 * evicting decisions early could reintroduce inconsistent outcomes across
 * sibling branches.
 */
public class InMemoryRecoveryDecisionStore implements RecoveryDecisionStore {

	private final ConcurrentMap<String, RecoveryDecision> recoveryDecisions = new ConcurrentHashMap<String, RecoveryDecision>();

	@Override
	public RecoveryDecision getOrRecord(String coordinatorId, RecoveryDecision proposedDecision) {
		return recoveryDecisions.computeIfAbsent(coordinatorId, ignored -> proposedDecision);
	}

}
