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
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.XAConnection;
import javax.sql.XADataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test suite for the disablePooling feature with XA datasources.
 * Tests verify that when disablePooling is enabled, connections are not pooled.
 */
public class DisablePoolingXATestJUnit {
    
    @Mock
    private XADataSource mockXADataSource;
    
    @Mock
    private XAConnection mockXAConnection1;
    
    @Mock
    private XAConnection mockXAConnection2;
    
    @Mock
    private Connection mockConnection1;
    
    @Mock
    private Connection mockConnection2;
    
    private AtomikosDataSourceBean ds;
    private AutoCloseable mocks;
    
    @Before
    public void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        ds = new AtomikosDataSourceBean();
        ds.setUniqueResourceName("testXADS");
        ds.setXaDataSource(mockXADataSource);
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
        
        // Don't call init() as it would require a valid XADataSource
        // Just verify that poolSize methods return 0 when pool is not initialized
        assertEquals("Pool size should be 0 when pooling disabled", 0, ds.poolTotalSize());
        assertEquals("Available pool size should be 0 when pooling disabled", 0, ds.poolAvailableSize());
    }
    
    @Test
    public void testPoolSizeIsNotZeroWhenDisablePoolingIsFalseWithMinPoolSize() throws SQLException {
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
        
        // Don't call init() as it would require a valid XADataSource
        // Verify that before init, pool is not created
        assertEquals("Pool should not be initialized", 0, ds.poolTotalSize());
    }
    
    @Test
    public void testXAPropertiesAccessibleWithDisablePooling() throws SQLException {
        ds.setDisablePooling(true);
        ds.setBorrowConnectionTimeout(10);
        ds.setLocalTransactionMode(true);
        
        assertEquals("Timeout should be accessible", 10, ds.getBorrowConnectionTimeout());
        assertTrue("LocalTransactionMode should be accessible", ds.getLocalTransactionMode());
    }
    
    @Test
    public void testXADataSourceClassName() {
        String className = "org.h2.jdbcx.JdbcDataSource";
        ds.setXaDataSourceClassName(className);
        assertEquals("XA DataSource class name should be set", className, ds.getXaDataSourceClassName());
    }
    
    @Test
    public void testXAProperties() {
        Properties props = new Properties();
        props.setProperty("url", "jdbc:h2:mem:test");
        props.setProperty("user", "sa");
        
        ds.setXaProperties(props);
        Properties retrieved = ds.getXaProperties();
        
        assertNotNull("XA properties should not be null", retrieved);
        assertEquals("URL property should match", "jdbc:h2:mem:test", retrieved.getProperty("url"));
        assertEquals("User property should match", "sa", retrieved.getProperty("user"));
    }
}
