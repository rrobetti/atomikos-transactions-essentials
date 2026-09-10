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
 * the manager instance. Only coordinators for which recovery actually had to
 * arbitrate a final outcome are tracked: entries are created when
 * {@link XARecoveryManager} reaches either the replay-commit path or the
 * presumed-abort path for a local prepared branch. Transactions that finish
 * normally without such recovery arbitration, newly discovered XIDs that are
 * still in their waiting period, and foreign in-doubt branches that are
 * deferred to remote recovery do not create entries here.
 * <p>
 * In practice this should therefore be a subset of all transactions, typically
 * limited to coordinators that remain prepared long enough to need local XA
 * recovery. The exact frequency depends on the application's failure patterns
 * and how often prepared branches survive into a recovery scan.
 * <p>
 * For rough sizing only, one million entries are expected to consume on the
 * order of 100-200 MB on a typical 64-bit HotSpot JVM with compressed oops,
 * assuming coordinator identifiers of roughly a few dozen characters. Actual
 * usage depends on JVM layout, load factor, and coordinator ID length.
 * <p>
 * That tradeoff is intentional for the open-source default: without a provably
 * safe cleanup hook in the current recovery flow, evicting decisions early
 * could reintroduce inconsistent outcomes across sibling branches.
 */
public class InMemoryRecoveryDecisionStore implements RecoveryDecisionStore {

	private final ConcurrentMap<String, RecoveryDecision> recoveryDecisions = new ConcurrentHashMap<String, RecoveryDecision>();

	@Override
	public RecoveryDecision getOrRecord(String coordinatorId, RecoveryDecision proposedDecision) {
		return recoveryDecisions.computeIfAbsent(coordinatorId, ignored -> proposedDecision);
	}

}
