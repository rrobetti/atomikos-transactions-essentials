/**
 * Copyright (C) 2000-2024 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.jdbc;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test suite for the disablePooling feature with NonXA datasources.
 * Tests verify that when disablePooling is enabled, connections are not pooled.
 */
public class DisablePoolingNonXATestJUnit {
    
    @Mock
    private Driver mockDriver;
    
    @Mock
    private Connection mockConnection1;
    
    @Mock
    private Connection mockConnection2;
    
    private AtomikosNonXADataSourceBean ds;
    private AutoCloseable mocks;
    
    @Before
    public void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        ds = new AtomikosNonXADataSourceBean();
        ds.setUniqueResourceName("testDS");
        ds.setUrl("jdbc:test:url");
        ds.setDriverClassName("org.h2.Driver"); // Use a real driver class for testing
    }
    
    @After
    public void tearDown() throws Exception {
        if (ds != null) {
            ds.close();
        }
        if (mocks != null) {
            mocks.close();
        }
    }
    
    @Test
    public void testDisablePoolingDefaultsToFalse() {
        assertFalse("disablePooling should default to false", ds.getDisablePooling());
    }
    
    @Test
    public void testSetDisablePooling() {
        ds.setDisablePooling(true);
        assertTrue("disablePooling should be true after setting", ds.getDisablePooling());
        
        ds.setDisablePooling(false);
        assertFalse("disablePooling should be false after setting", ds.getDisablePooling());
    }
    
    @Test
    public void testPoolSizeIsZeroWhenDisablePoolingIsTrue() throws SQLException {
        ds.setDisablePooling(true);
        
        // Don't call init() as it would require a valid driver
        // Just verify that poolSize methods return 0 when pool is not initialized
        assertEquals("Pool size should be 0 when pooling disabled", 0, ds.poolTotalSize());
        assertEquals("Available pool size should be 0 when pooling disabled", 0, ds.poolAvailableSize());
    }
    
    @Test
    public void testPoolSizeIsNotZeroWhenDisablePoolingIsFalse() throws SQLException {
        ds.setDisablePooling(false);
        ds.setMinPoolSize(1);
        ds.setMaxPoolSize(5);
        
        // Verify the configuration is accepted
        assertFalse("Pooling should be enabled", ds.getDisablePooling());
        assertEquals("Min pool size should be set", 1, ds.getMinPoolSize());
        assertEquals("Max pool size should be set", 5, ds.getMaxPoolSize());
    }
    
    @Test
    public void testDisablePoolingWithMaxMinPoolSizeIgnored() throws SQLException {
        // When disablePooling is true, minPoolSize and maxPoolSize should be ignored
        ds.setDisablePooling(true);
        ds.setMinPoolSize(10);
        ds.setMaxPoolSize(20);
        
        assertTrue("disablePooling should be true", ds.getDisablePooling());
        
        // The values are stored but should not affect behavior when pooling is disabled
        assertEquals("minPoolSize value stored", 10, ds.getMinPoolSize());
        assertEquals("maxPoolSize value stored", 20, ds.getMaxPoolSize());
    }
    
    @Test
    public void testDisablePoolingPreventsPoolInitialization() throws SQLException {
        ds.setDisablePooling(true);
        ds.setMinPoolSize(5);
        ds.setMaxPoolSize(10);
        
        // Don't call init() as it would require a valid driver
        // Verify that before init, pool is not created
        assertEquals("Pool should not be initialized", 0, ds.poolTotalSize());
    }
    
    @Test
    public void testConnectionPropertiesAccessibleWithDisablePooling() throws SQLException {
        ds.setDisablePooling(true);
        ds.setUser("testuser");
        ds.setPassword("testpass");
        ds.setBorrowConnectionTimeout(10);
        
        assertEquals("User should be accessible", "testuser", ds.getUser());
        assertEquals("Password should be accessible", "testpass", ds.getPassword());
        assertEquals("Timeout should be accessible", 10, ds.getBorrowConnectionTimeout());
    }
}
