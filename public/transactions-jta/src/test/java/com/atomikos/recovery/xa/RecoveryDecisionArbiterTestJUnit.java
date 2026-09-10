/**
 * Copyright (C) 2000-2026 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.recovery.xa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

public class RecoveryDecisionArbiterTestJUnit {

	@Test
	public void testFirstCommitWins() {
		RecoveryDecisionArbiter arbiter = new RecoveryDecisionArbiter(new InMemoryRecoveryDecisionStore());

		assertEquals(RecoveryDecision.COMMIT, arbiter.decide("coordinator", RecoveryDecision.COMMIT));
		assertEquals(RecoveryDecision.COMMIT, arbiter.decide("coordinator", RecoveryDecision.ABORT));
	}

	@Test
	public void testFirstAbortWins() {
		RecoveryDecisionArbiter arbiter = new RecoveryDecisionArbiter(new InMemoryRecoveryDecisionStore());

		assertEquals(RecoveryDecision.ABORT, arbiter.decide("coordinator", RecoveryDecision.ABORT));
		assertEquals(RecoveryDecision.ABORT, arbiter.decide("coordinator", RecoveryDecision.COMMIT));
	}

	@Test
	public void testRepeatedIdenticalDecisionsAreIdempotent() {
		RecoveryDecisionArbiter arbiter = new RecoveryDecisionArbiter(new InMemoryRecoveryDecisionStore());

		assertEquals(RecoveryDecision.COMMIT, arbiter.decide("coordinator", RecoveryDecision.COMMIT));
		assertEquals(RecoveryDecision.COMMIT, arbiter.decide("coordinator", RecoveryDecision.COMMIT));
	}

	@Test
	public void testDifferentCoordinatorsRemainIndependent() {
		RecoveryDecisionArbiter arbiter = new RecoveryDecisionArbiter(new InMemoryRecoveryDecisionStore());

		assertEquals(RecoveryDecision.COMMIT, arbiter.decide("coordinator1", RecoveryDecision.COMMIT));
		assertEquals(RecoveryDecision.ABORT, arbiter.decide("coordinator2", RecoveryDecision.ABORT));
	}

	@Test
	public void testConcurrentOppositeProposalsProduceOneConsistentDecision() throws Exception {
		for (int i = 0; i < 100; i++) {
			assertConcurrentWinnerIsConsistent();
		}
	}

	private void assertConcurrentWinnerIsConsistent() throws Exception {
		RecoveryDecisionArbiter arbiter = new RecoveryDecisionArbiter(new InMemoryRecoveryDecisionStore());
		ExecutorService executor = Executors.newFixedThreadPool(12);
		try {
			CyclicBarrier barrier = new CyclicBarrier(12);
			List<Callable<RecoveryDecision>> tasks = new ArrayList<Callable<RecoveryDecision>>();
			for (int i = 0; i < 6; i++) {
				tasks.add(newDecisionTask(arbiter, barrier, RecoveryDecision.COMMIT));
				tasks.add(newDecisionTask(arbiter, barrier, RecoveryDecision.ABORT));
			}
			List<Future<RecoveryDecision>> futures = executor.invokeAll(tasks);
			RecoveryDecision winner = futures.get(0).get();
			for (Future<RecoveryDecision> future : futures) {
				assertEquals(winner, future.get());
			}
			assertTrue(winner == RecoveryDecision.COMMIT || winner == RecoveryDecision.ABORT);
		} finally {
			executor.shutdownNow();
		}
	}

	private Callable<RecoveryDecision> newDecisionTask(final RecoveryDecisionArbiter arbiter, final CyclicBarrier barrier,
			final RecoveryDecision proposedDecision) {
		return new Callable<RecoveryDecision>() {
			@Override
			public RecoveryDecision call() throws Exception {
				barrier.await();
				return arbiter.decide("coordinator", proposedDecision);
			}
		};
	}

}
