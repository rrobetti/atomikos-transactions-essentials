/**
 * Copyright (C) 2000-2026 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.recovery.xa;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.atomikos.datasource.xa.XID;
import com.atomikos.icatch.event.Event;
import com.atomikos.icatch.event.EventListener;
import com.atomikos.icatch.event.transaction.ParticipantHeuristicEvent;
import com.atomikos.recovery.PendingTransactionRecord;
import com.atomikos.recovery.TxState;
import com.atomikos.publish.EventPublisher;

public class XARecoveryManagerTestJUnit {

	private static final long START_OF_RECOVERY_SCAN = 100;

	private XARecoveryManager sut;
	private XAResource xaResource;

	@Before
	public void setUp() {
		sut = new XARecoveryManager("tm", new InMemoryRecoveryDecisionStore());
		xaResource = Mockito.mock(XAResource.class);
	}

	@After
	public void tearDown() {
		XARecoveryManager.installXARecoveryManager(null);
	}

	@Test
	public void testCommitFirstThenPresumedAbortClassificationCommitsBothSiblingBranches() throws Exception {
		XID commitBranch = new XID("global", "branch1", "resource");
		XID abortClassifiedBranch = new XID("global", "branch2", "resource");

		assertTrue(sut.recoverXids(Collections.singletonList(commitBranch), previousXids(), expiredCommitting("global"), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));
		assertTrue(sut.recoverXids(Collections.singletonList(abortClassifiedBranch), previousXids(abortClassifiedBranch), Collections.<PendingTransactionRecord>emptyList(), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));

		Mockito.verify(xaResource).commit(commitBranch, false);
		Mockito.verify(xaResource).commit(abortClassifiedBranch, false);
		Mockito.verify(xaResource, Mockito.never()).rollback(Mockito.any(XID.class));
	}

	@Test
	public void testAbortFirstThenReplayCommitClassificationRollsBackBothSiblingBranches() throws Exception {
		XID abortBranch = new XID("global", "branch1", "resource");
		XID commitClassifiedBranch = new XID("global", "branch2", "resource");

		assertTrue(sut.recoverXids(Collections.singletonList(abortBranch), previousXids(abortBranch), Collections.<PendingTransactionRecord>emptyList(), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));
		assertTrue(sut.recoverXids(Collections.singletonList(commitClassifiedBranch), previousXids(), expiredCommitting("global"), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));

		Mockito.verify(xaResource).rollback(abortBranch);
		Mockito.verify(xaResource).rollback(commitClassifiedBranch);
		Mockito.verify(xaResource, Mockito.never()).commit(Mockito.any(XID.class), Mockito.eq(false));
	}

	@Test
	public void testTransientCommitFailureIsRetainedForRetryAndDoesNotPermitAbortLater() throws Exception {
		XID failingCommitBranch = new XID("global", "branch1", "resource");
		XID laterAbortClassifiedBranch = new XID("global", "branch2", "resource");
		PreviousXidRepository failedCommitRepository = previousXids();
		Mockito.doThrow(transientFailure()).doNothing().when(xaResource).commit(Mockito.any(XID.class), Mockito.eq(false));

		assertFalse(sut.recoverXids(Collections.singletonList(failingCommitBranch), failedCommitRepository, expiredCommitting("global"), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));
		assertTrue(sut.recoverXids(Collections.singletonList(laterAbortClassifiedBranch), previousXids(laterAbortClassifiedBranch), Collections.<PendingTransactionRecord>emptyList(), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));

		Mockito.verify(failedCommitRepository).remember(failingCommitBranch, START_OF_RECOVERY_SCAN + 1);
		Mockito.verify(xaResource).commit(failingCommitBranch, false);
		Mockito.verify(xaResource).commit(laterAbortClassifiedBranch, false);
		Mockito.verify(xaResource, Mockito.never()).rollback(Mockito.any(XID.class));
	}

	@Test
	public void testHeuristicHandlingRemainsUnchangedForAuthoritativeCommit() throws Exception {
		XID xid = new XID("global", "branch", "resource");
		EventListener listener = Mockito.mock(EventListener.class);
		EventPublisher.INSTANCE.registerEventListener(listener);
		Mockito.doThrow(xaException(XAException.XA_HEURRB)).when(xaResource).commit(xid, false);

		assertTrue(sut.recoverXids(Collections.singletonList(xid), previousXids(), expiredCommitting("global"), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));

		Mockito.verify(xaResource).forget(xid);
		ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
		Mockito.verify(listener).eventOccurred(eventCaptor.capture());
		assertTrue(eventCaptor.getValue() instanceof ParticipantHeuristicEvent);
		assertTrue(((ParticipantHeuristicEvent) eventCaptor.getValue()).state == TxState.HEUR_ABORTED);
	}

	@Test
	public void testXaerNotaAndXaerInvalRemainSuccessfulOutcomes() throws Exception {
		XID commitXid = new XID("global1", "branch1", "resource");
		XID abortXid = new XID("global2", "branch2", "resource");
		PreviousXidRepository commitRepository = previousXids();
		PreviousXidRepository abortRepository = previousXids(abortXid);
		Mockito.doThrow(xaException(XAException.XAER_NOTA)).when(xaResource).commit(commitXid, false);
		Mockito.doThrow(xaException(XAException.XAER_INVAL)).when(xaResource).rollback(abortXid);

		assertTrue(sut.recoverXids(Collections.singletonList(commitXid), commitRepository, expiredCommitting("global1"), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));
		assertTrue(sut.recoverXids(Collections.singletonList(abortXid), abortRepository, Collections.<PendingTransactionRecord>emptyList(), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));

		Mockito.verify(commitRepository, Mockito.never()).remember(Mockito.any(XID.class), Mockito.anyLong());
		Mockito.verify(abortRepository, Mockito.never()).remember(Mockito.any(XID.class), Mockito.anyLong());
	}

	@Test
	public void testForeignIndoubtBranchesAreDeferredWithoutRecordingAbortDecision() throws Exception {
		XID deferredBranch = new XID("global", "branch1", "resource");
		XID laterCommitBranch = new XID("global", "branch2", "resource");
		PreviousXidRepository deferredRepository = previousXids(deferredBranch);

		assertTrue(sut.recoverXids(Collections.singletonList(deferredBranch), deferredRepository, Collections.<PendingTransactionRecord>emptyList(), foreignIndoubt("global"), xaResource, START_OF_RECOVERY_SCAN));
		assertTrue(sut.recoverXids(Collections.singletonList(laterCommitBranch), previousXids(), expiredCommitting("global"), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN));

		Mockito.verify(deferredRepository).remember(deferredBranch, START_OF_RECOVERY_SCAN + 1);
		Mockito.verify(xaResource).commit(laterCommitBranch, false);
		Mockito.verify(xaResource, Mockito.never()).rollback(Mockito.any(XID.class));
	}

	@Test
	public void testInstallingFreshXaRecoveryManagerCreatesFreshInMemoryDecisionScope() throws Exception {
		XID abortXid = new XID("global", "branch1", "resource");
		XID laterCommitXid = new XID("global", "branch2", "resource");
		XARecoveryManager.installXARecoveryManager("tm");
		XARecoveryManager.getInstance().recoverXids(Collections.singletonList(abortXid), previousXids(abortXid), Collections.<PendingTransactionRecord>emptyList(), Collections.<PendingTransactionRecord>emptyList(), xaResource, START_OF_RECOVERY_SCAN);
		Mockito.verify(xaResource).rollback(abortXid);
		XARecoveryManager.installXARecoveryManager("tm");
		XAResource freshXaResource = Mockito.mock(XAResource.class);

		assertTrue(XARecoveryManager.getInstance().recoverXids(Collections.singletonList(laterCommitXid), previousXids(), expiredCommitting("global"), Collections.<PendingTransactionRecord>emptyList(), freshXaResource, START_OF_RECOVERY_SCAN));

		Mockito.verify(freshXaResource).commit(laterCommitXid, false);
		Mockito.verify(freshXaResource, Mockito.never()).rollback(Mockito.any(XID.class));
	}

	private PreviousXidRepository previousXids(XID... expiredPreviousXids) {
		PreviousXidRepository ret = Mockito.mock(PreviousXidRepository.class);
		Mockito.when(ret.findXidsExpiredAt(START_OF_RECOVERY_SCAN)).thenReturn(Arrays.asList(expiredPreviousXids));
		return ret;
	}

	private Collection<PendingTransactionRecord> expiredCommitting(String coordinatorId) {
		return Collections.singletonList(new PendingTransactionRecord(coordinatorId, TxState.COMMITTING, 0, "domain"));
	}

	private Collection<PendingTransactionRecord> foreignIndoubt(String coordinatorId) {
		return Collections.singletonList(new PendingTransactionRecord(coordinatorId, TxState.IN_DOUBT, 0, "foreign"));
	}

	private XAException transientFailure() {
		return xaException(XAException.XAER_RMERR);
	}

	private XAException xaException(int errorCode) {
		XAException ret = new XAException(errorCode);
		ret.errorCode = errorCode;
		return ret;
	}

}
