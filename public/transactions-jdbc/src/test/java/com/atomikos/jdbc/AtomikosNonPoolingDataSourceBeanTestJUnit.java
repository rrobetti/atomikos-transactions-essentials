/**
 * Copyright (C) 2000-2026 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.jdbc;

import static org.mockito.ArgumentMatchers.*;

import org.junit.Test;

public class AtomikosNonPoolingDataSourceBeanTestJUnit {

	private AtomikosNonPoolingDataSourceBean sut = new AtomikosNonPoolingDataSourceBean();

	@Test(expected = UnsupportedOperationException.class)
	public void testMaxPoolSizeIsNotSupported() throws Exception {
		sut.setMaxPoolSize(anyInt());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void testMinPoolSizeIsNotSupported() throws Exception {
		sut.setMinPoolSize(anyInt());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void testPoolSizeIsNotSupported() throws Exception {
		sut.setPoolSize(anyInt());
	}
	
	@Test(expected = UnsupportedOperationException.class)
	public void testMaintenanceIntervalIsNotSupported() throws Exception {
		sut.setMaintenanceInterval(anyInt());
	}
	
	@Test(expected = UnsupportedOperationException.class)
	public void testConcurrentConnectionValidationIsNotSupported() throws Exception {
		sut.setConcurrentConnectionValidation(anyBoolean());
	}
	
	@Test(expected = UnsupportedOperationException.class)
	public void testMaxIdleTimeIsNotSupported() throws Exception {
		sut.setMaxIdleTime(anyInt());
	}
	
	@Test(expected = UnsupportedOperationException.class)
	public void testMaxLifetimeIsNotSupported() throws Exception {
		sut.setMaxLifetime(anyInt());
	}
	
	@Test
	public void testAssertPoolSizeSettingsDoesNotThrow() throws AtomikosSQLException {
		sut.assertPoolSizeSettings();
	}
}
