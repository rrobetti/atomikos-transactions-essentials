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
import static org.junit.Assert.fail;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class RecoveryDecisionStoreFactoryTestJUnit {

	@Test
	public void testDefaultsToInMemoryStoreIfNoProviderIsConfigured() {
		RecoveryDecisionStore store = RecoveryDecisionStoreFactory.create(new URLClassLoader(new URL[0], getClass().getClassLoader()));

		assertTrue(store instanceof InMemoryRecoveryDecisionStore);
	}

	@Test
	public void testCustomServiceLoaderProviderCanReplaceDefault() throws Exception {
		Path servicesDirectory = Files.createTempDirectory("recovery-decision-store");
		Files.createDirectories(servicesDirectory.resolve("META-INF/services"));
		Files.write(servicesDirectory.resolve("META-INF/services/" + RecoveryDecisionStore.class.getName()),
				TestRecoveryDecisionStore.class.getName().getBytes(StandardCharsets.UTF_8));
		RecoveryDecisionStore store = RecoveryDecisionStoreFactory
				.create(new URLClassLoader(new URL[] { servicesDirectory.toUri().toURL() }, getClass().getClassLoader()));

		assertTrue(store instanceof TestRecoveryDecisionStore);
		assertEquals(RecoveryDecision.COMMIT, store.getOrRecord("coordinator", RecoveryDecision.COMMIT));
	}

	@Test
	public void testMultipleProvidersFailFast() throws Exception {
		Path servicesDirectory = Files.createTempDirectory("recovery-decision-store");
		Files.createDirectories(servicesDirectory.resolve("META-INF/services"));
		Files.write(servicesDirectory.resolve("META-INF/services/" + RecoveryDecisionStore.class.getName()),
				(TestRecoveryDecisionStore.class.getName() + System.lineSeparator() + AlternateTestRecoveryDecisionStore.class.getName())
						.getBytes(StandardCharsets.UTF_8));
		try {
			RecoveryDecisionStoreFactory.create(new URLClassLoader(new URL[] { servicesDirectory.toUri().toURL() }, getClass().getClassLoader()));
			fail("Expected IllegalStateException");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("More than one RecoveryDecisionStore"));
		}
	}

	public static class TestRecoveryDecisionStore extends InMemoryRecoveryDecisionStore {
	}

	public static class AlternateTestRecoveryDecisionStore extends InMemoryRecoveryDecisionStore {
	}

}
