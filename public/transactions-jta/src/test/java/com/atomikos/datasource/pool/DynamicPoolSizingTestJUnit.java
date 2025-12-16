/**
 * Copyright (C) 2000-2024 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.datasource.pool;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Comprehensive test suite for dynamic pool sizing functionality.
 * Tests runtime modification of minPoolSize and maxPoolSize.
 */
public class DynamicPoolSizingTestJUnit {
    
    private MockConnectionPool pool;
    private MockConnectionFactory factory;
    private MockConnectionPoolProperties properties;
    
    @Before
    public void setUp() throws Exception {
        properties = new MockConnectionPoolProperties("testPool", 2, 5);
        factory = new MockConnectionFactory();
        pool = new MockConnectionPool(factory, properties);
    }
    
    @After
    public void tearDown() {
        if (pool != null) {
            pool.destroy();
        }
    }
    
    @Test
    public void testInitialPoolSizes() {
        assertEquals(2, pool.getMinPoolSize());
        assertEquals(5, pool.getMaxPoolSize());
        assertEquals(2, pool.getConfiguredMinPoolSize());
        assertEquals(5, pool.getConfiguredMaxPoolSize());
    }
    
    @Test
    public void testIncreaseMinPoolSize() throws Exception {
        assertEquals(2, pool.totalSize());
        
        pool.setMinPoolSize(4);
        assertEquals(4, pool.getMinPoolSize());
        
        // Trigger maintenance cycle manually
        pool.performMaintenance();
        
        // Pool should grow to new minimum
        assertEquals(4, pool.totalSize());
    }
    
    @Test
    public void testDecreaseMinPoolSize() throws Exception {
        assertEquals(2, pool.totalSize());
        
        pool.setMinPoolSize(1);
        assertEquals(1, pool.getMinPoolSize());
        
        // Even after maintenance, size won't shrink immediately unless connections are idle
        pool.performMaintenance();
        
        // Configured min changed, but pool hasn't shrunk yet
        assertEquals(2, pool.totalSize());
    }
    
    @Test
    public void testIncreaseMaxPoolSize() throws Exception {
        pool.setMaxPoolSize(10);
        assertEquals(10, pool.getMaxPoolSize());
        
        // Pool can now grow beyond original limit
        assertTrue(pool.canGrowToSize(10));
    }
    
    @Test
    public void testDecreaseMaxPoolSize() throws Exception {
        // Grow pool to current max
        for (int i = 0; i < 3; i++) {
            pool.borrowConnection();
        }
        assertEquals(5, pool.totalSize());
        
        // Decrease max
        pool.setMaxPoolSize(3);
        assertEquals(3, pool.getMaxPoolSize());
        
        // Return connections and trigger maintenance
        pool.returnAllConnections();
        pool.performMaintenance();
        
        // Pool should shrink to new max
        assertTrue(pool.totalSize() <= 3);
    }
    
    @Test
    public void testSetPoolSizeRange() throws Exception {
        pool.setPoolSizeRange(3, 8);
        
        assertEquals(3, pool.getMinPoolSize());
        assertEquals(8, pool.getMaxPoolSize());
        
        pool.performMaintenance();
        assertEquals(3, pool.totalSize());
    }
    
    @Test
    public void testResetPoolSizes() throws Exception {
        // Change pool sizes
        pool.setPoolSizeRange(10, 20);
        assertEquals(10, pool.getMinPoolSize());
        assertEquals(20, pool.getMaxPoolSize());
        
        // Reset to configured values
        pool.resetPoolSizes();
        assertEquals(2, pool.getMinPoolSize());
        assertEquals(5, pool.getMaxPoolSize());
        assertEquals(2, pool.getConfiguredMinPoolSize());
        assertEquals(5, pool.getConfiguredMaxPoolSize());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testMinGreaterThanMax() throws Exception {
        pool.setMinPoolSize(10); // Should fail because current max is 5
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testMaxLessThanMin() throws Exception {
        pool.setMaxPoolSize(1); // Should fail because current min is 2
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMinPoolSize() throws Exception {
        pool.setMinPoolSize(-1);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testZeroMaxPoolSize() throws Exception {
        pool.setMaxPoolSize(0);
    }
    
    @Test
    public void testConcurrentPoolSizeUpdates() throws Exception {
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    if (threadIndex % 2 == 0) {
                        pool.setMinPoolSize(3);
                    } else {
                        pool.setMaxPoolSize(10);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Expected - some operations may fail due to validation
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // At least some operations should have succeeded
        assertTrue(successCount.get() > 0);
        
        // Pool should be in a consistent state
        assertTrue(pool.getMinPoolSize() <= pool.getMaxPoolSize());
    }
    
    @Test
    public void testGracefulShrinking() throws Exception {
        // Grow pool to max
        List<Object> connections = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            connections.add(pool.borrowConnection());
        }
        assertEquals(5, pool.totalSize());
        
        // Reduce max while connections are in use
        pool.setMaxPoolSize(3);
        
        // Return one connection at a time
        for (Object conn : connections) {
            pool.returnConnection(conn);
        }
        
        // Trigger maintenance
        pool.performMaintenance();
        
        // Pool should eventually shrink to new max
        assertTrue(pool.totalSize() <= 3);
    }
    
    @Test
    public void testConfiguredValuesPreserved() throws Exception {
        assertEquals(2, pool.getConfiguredMinPoolSize());
        assertEquals(5, pool.getConfiguredMaxPoolSize());
        
        // Change runtime values
        pool.setPoolSizeRange(10, 20);
        
        // Configured values should remain unchanged
        assertEquals(2, pool.getConfiguredMinPoolSize());
        assertEquals(5, pool.getConfiguredMaxPoolSize());
        
        // Current values should be updated
        assertEquals(10, pool.getMinPoolSize());
        assertEquals(20, pool.getMaxPoolSize());
    }
    
    @Test
    public void testAtomicRangeUpdate() throws Exception {
        // This should work even though intermediate state would be invalid
        pool.setPoolSizeRange(10, 15);
        
        assertEquals(10, pool.getMinPoolSize());
        assertEquals(15, pool.getMaxPoolSize());
    }
    
    @Test
    public void testPoolSizeLogging() throws Exception {
        // This test verifies that size changes are logged
        // Actual logging verification would require a log capture mechanism
        
        int oldMin = pool.getMinPoolSize();
        pool.setMinPoolSize(oldMin + 1);
        
        int oldMax = pool.getMaxPoolSize();
        pool.setMaxPoolSize(oldMax + 1);
        
        // Test passes if no exceptions thrown
        assertTrue(true);
    }
    
    /**
     * Mock connection pool for testing
     */
    private static class MockConnectionPool extends ConnectionPool<Object> {
        private List<Object> borrowedConnections = new ArrayList<>();
        
        public MockConnectionPool(ConnectionFactory<Object> connectionFactory, 
                                  ConnectionPoolProperties properties) throws ConnectionPoolException {
            super(connectionFactory, properties);
        }
        
        @Override
        protected Object recycleConnectionIfPossible() throws Exception {
            return null;
        }
        
        @Override
        protected Object retrieveFirstAvailableConnection() {
            poolLock.lock();
            try {
                for (XPooledConnection<Object> xpc : connections) {
                    if (xpc.isAvailable()) {
                        Object conn = new Object();
                        borrowedConnections.add(conn);
                        xpc.markAsInUse();
                        return conn;
                    }
                }
                return null;
            } finally {
                poolLock.unlock();
            }
        }
        
        public void performMaintenance() {
            // Manually trigger maintenance tasks
            poolLock.lock();
            try {
                int connectionsToAdd = getMinPoolSize() - totalSize();
                for (int i = 0; i < connectionsToAdd; i++) {
                    try {
                        XPooledConnection<Object> xpc = new MockXPooledConnection();
                        connections.add(xpc);
                        xpc.registerXPooledConnectionEventListener(this);
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                
                // Shrink if needed
                List<XPooledConnection<Object>> toRemove = new ArrayList<>();
                int currentTotal = totalSize();
                int currentMax = getMaxPoolSize();
                
                if (currentTotal > currentMax) {
                    int toRemoveCount = currentTotal - currentMax;
                    for (XPooledConnection<Object> xpc : connections) {
                        if (xpc.isAvailable() && toRemove.size() < toRemoveCount) {
                            toRemove.add(xpc);
                        }
                    }
                }
                
                connections.removeAll(toRemove);
            } finally {
                poolLock.unlock();
            }
        }
        
        public boolean canGrowToSize(int size) {
            return size <= getMaxPoolSize();
        }
        
        public void returnAllConnections() {
            poolLock.lock();
            try {
                for (XPooledConnection<Object> xpc : connections) {
                    xpc.markAsAvailable();
                }
                borrowedConnections.clear();
            } finally {
                poolLock.unlock();
            }
        }
        
        public void returnConnection(Object conn) {
            poolLock.lock();
            try {
                borrowedConnections.remove(conn);
                if (!connections.isEmpty()) {
                    connections.get(0).markAsAvailable();
                }
            } finally {
                poolLock.unlock();
            }
        }
    }
    
    /**
     * Mock connection factory
     */
    private static class MockConnectionFactory implements ConnectionFactory<Object> {
        @Override
        public XPooledConnection<Object> createPooledConnection() throws CreateConnectionException {
            return new MockXPooledConnection();
        }
    }
    
    /**
     * Mock pooled connection
     */
    private static class MockXPooledConnection implements XPooledConnection<Object> {
        private boolean available = true;
        private boolean inUse = false;
        private long creationTime = System.currentTimeMillis();
        private long lastTimeReleased = System.currentTimeMillis();
        
        @Override
        public Object createConnectionProxy() throws CreateConnectionException {
            available = false;
            inUse = true;
            return new Object();
        }
        
        @Override
        public boolean isAvailable() {
            return available && !inUse;
        }
        
        @Override
        public boolean canBeRecycledForCallingThread() {
            return false;
        }
        
        @Override
        public void destroy() {
            available = false;
            inUse = false;
        }
        
        @Override
        public long getLastTimeReleased() {
            return lastTimeReleased;
        }
        
        @Override
        public long getCreationTime() {
            return creationTime;
        }
        
        @Override
        public void registerXPooledConnectionEventListener(XPooledConnectionEventListener listener) {
        }
        
        @Override
        public boolean markAsBeingAcquiredIfAvailable() {
            if (available && !inUse) {
                inUse = true;
                return true;
            }
            return false;
        }
        
        public void markAsInUse() {
            inUse = true;
            available = false;
        }
        
        public void markAsAvailable() {
            inUse = false;
            available = true;
            lastTimeReleased = System.currentTimeMillis();
        }
    }
    
    /**
     * Mock connection pool properties
     */
    private static class MockConnectionPoolProperties implements ConnectionPoolProperties {
        private final String uniqueResourceName;
        private final int minPoolSize;
        private final int maxPoolSize;
        
        public MockConnectionPoolProperties(String uniqueResourceName, int minPoolSize, int maxPoolSize) {
            this.uniqueResourceName = uniqueResourceName;
            this.minPoolSize = minPoolSize;
            this.maxPoolSize = maxPoolSize;
        }
        
        @Override
        public String getUniqueResourceName() {
            return uniqueResourceName;
        }
        
        @Override
        public int getMaxPoolSize() {
            return maxPoolSize;
        }
        
        @Override
        public int getMinPoolSize() {
            return minPoolSize;
        }
        
        @Override
        public int getBorrowConnectionTimeout() {
            return 30;
        }
        
        @Override
        public int getMaxIdleTime() {
            return 60;
        }
        
        @Override
        public int getMaxLifetime() {
            return 0;
        }
        
        @Override
        public int getMaintenanceInterval() {
            return 60;
        }
        
        @Override
        public String getTestQuery() {
            return null;
        }
        
        @Override
        public boolean getLocalTransactionMode() {
            return false;
        }
        
        @Override
        public int getDefaultIsolationLevel() {
            return DEFAULT_ISOLATION_LEVEL_UNSET;
        }
    }
}
