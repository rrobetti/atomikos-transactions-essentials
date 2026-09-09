/**
 * Copyright (C) 2000-2026 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.recovery.xa;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import com.atomikos.icatch.config.Configuration;
import com.atomikos.logging.Logger;
import com.atomikos.logging.LoggerFactory;

class RecoveryDecisionStoreFactory {

	private static final Logger LOGGER = LoggerFactory.createLogger(RecoveryDecisionStoreFactory.class);

	static RecoveryDecisionStore create() {
		return create(Configuration.class.getClassLoader());
	}

	static RecoveryDecisionStore create(ClassLoader classLoader) {
		try {
			ServiceLoader<RecoveryDecisionStore> loader = ServiceLoader.load(RecoveryDecisionStore.class, classLoader);
			Iterator<RecoveryDecisionStore> iterator = loader.iterator();
			if (!iterator.hasNext()) {
				return new InMemoryRecoveryDecisionStore();
			}
			RecoveryDecisionStore ret = iterator.next();
			if (iterator.hasNext()) {
				String msg = "More than one RecoveryDecisionStore found in classpath - error in configuration!";
				LOGGER.logFatal(msg);
				throw new IllegalStateException(msg);
			}
			return ret;
		} catch (ServiceConfigurationError error) {
			String msg = "Error loading RecoveryDecisionStore from classpath";
			LOGGER.logFatal(msg, error);
			throw new IllegalStateException(msg, error);
		}
	}

}
