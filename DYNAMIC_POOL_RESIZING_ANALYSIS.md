# Analysis Report: Dynamic Connection Pool Resizing

## Executive Summary

This document analyzes the requirements and design considerations for implementing runtime (dynamic) resizing of the Atomikos connection pool. The feature will allow changing `minPoolSize` and `maxPoolSize` at runtime, with graceful handling of pool shrinking and growth.

## Current Architecture

### Key Components

1. **ConnectionPool** (`com.atomikos.datasource.pool.ConnectionPool`)
   - Abstract base class managing the connection pool lifecycle
   - Maintains a list of `XPooledConnection` objects
   - Handles connection borrowing, returning, and maintenance
   - Has a maintenance timer that runs periodically

2. **ConnectionPoolProperties** (`com.atomikos.datasource.pool.ConnectionPoolProperties`)
   - Interface defining pool configuration properties
   - Currently provides read-only access via getters: `getMinPoolSize()`, `getMaxPoolSize()`
   - No setters defined in the interface

3. **AbstractDataSourceBean** (`com.atomikos.jdbc.internal.AbstractDataSourceBean`)
   - Implements `ConnectionPoolProperties`
   - Stores pool configuration in private fields: `minPoolSize`, `maxPoolSize`
   - Provides setters but only effective BEFORE pool initialization
   - Creates the ConnectionPool during `init()`

4. **Concrete Implementations**
   - `ConnectionPoolWithSynchronizedValidation` - uses synchronized methods for thread safety
   - `ConnectionPoolWithConcurrentValidation` - uses finer-grained locking for better concurrency

### Current Pool Lifecycle

1. **Initialization** (`init()`):
   - Creates ConnectionPool with initial configuration
   - Pool reads properties once via `ConnectionPoolProperties` interface
   - Adds connections to reach `minPoolSize`
   - Starts maintenance timer

2. **Maintenance Cycle** (runs every `maintenanceInterval` seconds):
   - Removes connections exceeding `maxLifetime`
   - Adds connections if below `minPoolSize`
   - Removes idle connections if above `minPoolSize` (respecting `maxIdleTime`)

3. **Connection Borrowing**:
   - Reuses existing open connections for the calling thread
   - Returns available connections from pool
   - Grows pool if needed (up to `maxPoolSize`)
   - Waits/blocks if pool is exhausted

## Requirements Analysis

### Functional Requirements

1. **Dynamic minPoolSize Update**
   - Allow setting minPoolSize at runtime
   - Pool should eventually reach new minimum
   - If new min > current size: add connections gradually
   - If new min < current size: allow natural shrinkage via maintenance cycle

2. **Dynamic maxPoolSize Update**
   - Allow setting maxPoolSize at runtime
   - Prevent new connections beyond new maximum
   - If new max < current size: gracefully close excess connections when they become available
   - Never forcefully close connections in active use

3. **Graceful Shrinking**
   - When reducing pool size, wait for connections to be returned
   - Only close available (idle) connections
   - Respect ongoing work - do not interrupt active connections
   - Coordinate with existing maintenance cycle

4. **Validation**
   - Ensure minPoolSize <= maxPoolSize
   - Ensure minPoolSize >= 0
   - Ensure maxPoolSize >= 1
   - Handle concurrent updates safely

### Non-Functional Requirements

1. **Thread Safety**
   - Pool operations are already synchronized
   - New setters must maintain thread safety
   - Prevent race conditions during size adjustments

2. **Performance**
   - Minimal impact on connection borrowing operations
   - Leverage existing maintenance cycle for gradual adjustments
   - Avoid blocking operations during resize

3. **Backward Compatibility**
   - Existing API must remain unchanged
   - New methods are additions, not modifications
   - No breaking changes to ConnectionPoolProperties interface

## Proposed Solution

### Design Approach

The solution involves three main components:

1. **Add Dynamic Property Storage in ConnectionPool**
   - Store current min/max as volatile fields
   - Allow atomic updates via new setter methods
   - Maintenance cycle reads latest values

2. **Add Setters to DataSource Beans**
   - `setMinPoolSize(int)` and `setMaxPoolSize(int)` methods that work post-initialization
   - Validate inputs and delegate to ConnectionPool
   - Provide atomic `setPoolSizeRange(int min, int max)` for simultaneous updates

3. **Enhance Maintenance Cycle**
   - Already handles adding connections to reach minPoolSize
   - Already handles removing idle connections above minPoolSize
   - Modify to respect new maxPoolSize when shrinking
   - Add logic to aggressively shrink when current size > new maxPoolSize

### Detailed Changes Required

#### 1. ConnectionPool Class

**New Fields:**
```java
// Volatile to ensure visibility across threads
private volatile int currentMinPoolSize;
private volatile int currentMaxPoolSize;
```

**Constructor Changes:**
```java
public ConnectionPool(...) {
    // ... existing code ...
    this.currentMinPoolSize = properties.getMinPoolSize();
    this.currentMaxPoolSize = properties.getMaxPoolSize();
}
```

**New Public Methods:**
```java
/**
 * Updates the minimum pool size at runtime.
 * The pool will gradually grow to meet the new minimum during maintenance cycles.
 * 
 * @param minPoolSize new minimum pool size (must be >= 0 and <= maxPoolSize)
 * @throws IllegalArgumentException if constraints are violated
 */
public synchronized void setMinPoolSize(int minPoolSize) {
    validateMinPoolSize(minPoolSize, currentMaxPoolSize);
    this.currentMinPoolSize = minPoolSize;
    // Trigger immediate maintenance cycle to start growing if needed
    notifyMaintenanceCycle();
}

/**
 * Updates the maximum pool size at runtime.
 * If the new maximum is less than current pool size, excess connections
 * will be gradually closed as they become available.
 * 
 * @param maxPoolSize new maximum pool size (must be >= minPoolSize and >= 1)
 * @throws IllegalArgumentException if constraints are violated
 */
public synchronized void setMaxPoolSize(int maxPoolSize) {
    validateMaxPoolSize(currentMinPoolSize, maxPoolSize);
    this.currentMaxPoolSize = maxPoolSize;
    // Trigger immediate maintenance cycle to start shrinking if needed
    notifyMaintenanceCycle();
}

/**
 * Updates both min and max pool sizes atomically.
 * 
 * @param minPoolSize new minimum pool size
 * @param maxPoolSize new maximum pool size
 * @throws IllegalArgumentException if constraints are violated
 */
public synchronized void setPoolSizeRange(int minPoolSize, int maxPoolSize) {
    validateMinPoolSize(minPoolSize, maxPoolSize);
    validateMaxPoolSize(minPoolSize, maxPoolSize);
    this.currentMinPoolSize = minPoolSize;
    this.currentMaxPoolSize = maxPoolSize;
    notifyMaintenanceCycle();
}

/**
 * Gets the current minimum pool size (may differ from initial configuration).
 */
public int getMinPoolSize() {
    return currentMinPoolSize;
}

/**
 * Gets the current maximum pool size (may differ from initial configuration).
 */
public int getMaxPoolSize() {
    return currentMaxPoolSize;
}
```

**Private Validation Methods:**
```java
private void validateMinPoolSize(int minPoolSize, int maxPoolSize) {
    if (minPoolSize < 0) {
        throw new IllegalArgumentException("minPoolSize must be >= 0, was: " + minPoolSize);
    }
    if (minPoolSize > maxPoolSize) {
        throw new IllegalArgumentException(
            "minPoolSize (" + minPoolSize + ") must be <= maxPoolSize (" + maxPoolSize + ")");
    }
}

private void validateMaxPoolSize(int minPoolSize, int maxPoolSize) {
    if (maxPoolSize < 1) {
        throw new IllegalArgumentException("maxPoolSize must be >= 1, was: " + maxPoolSize);
    }
    if (minPoolSize > maxPoolSize) {
        throw new IllegalArgumentException(
            "minPoolSize (" + minPoolSize + ") must be <= maxPoolSize (" + maxPoolSize + ")");
    }
}

private void notifyMaintenanceCycle() {
    // Option 1: Let next scheduled cycle handle it (no action needed)
    // Option 2: Wake up maintenance thread immediately (requires refactoring timer)
    // Recommendation: Start with Option 1 for simplicity
}
```

**Modified Methods:**

`addConnectionsIfMinPoolSizeNotReached()`:
```java
private synchronized void addConnectionsIfMinPoolSizeNotReached() {
    int connectionsToAdd = currentMinPoolSize - totalSize(); // Use currentMinPoolSize
    // ... rest unchanged ...
}
```

`removeIdleConnectionsIfMinPoolSizeExceeded()`:
```java
private synchronized void removeIdleConnectionsIfMinPoolSizeExceeded() {
    if (connections == null || properties.getMaxIdleTime() <= 0)
        return;

    List<XPooledConnection<ConnectionType>> connectionsToRemove = new ArrayList<>();
    
    // Calculate based on current min/max
    int maxConnectionsToRemove = totalSize() - currentMinPoolSize;
    
    // If current size > max, we need to aggressively shrink
    if (totalSize() > currentMaxPoolSize) {
        maxConnectionsToRemove = Math.max(maxConnectionsToRemove, 
                                          totalSize() - currentMaxPoolSize);
    }
    
    if (maxConnectionsToRemove > 0) {
        // ... existing logic with maxConnectionsToRemove ...
    }
}
```

`canGrow()`:
```java
private boolean canGrow() {
    return totalSize() < currentMaxPoolSize; // Use currentMaxPoolSize
}
```

#### 2. AbstractDataSourceBean Class

**Modified Setters:**
```java
/**
 * Sets the minimum pool size. Can be called at runtime after initialization.
 * 
 * @param minPoolSize The new minimum pool size
 */
public void setMinPoolSize(int minPoolSize) {
    this.minPoolSize = minPoolSize;
    if (connectionPool != null) {
        connectionPool.setMinPoolSize(minPoolSize);
    }
}

/**
 * Sets the maximum pool size. Can be called at runtime after initialization.
 * 
 * @param maxPoolSize The new maximum pool size
 */
public void setMaxPoolSize(int maxPoolSize) {
    this.maxPoolSize = maxPoolSize;
    if (connectionPool != null) {
        connectionPool.setMaxPoolSize(maxPoolSize);
    }
}

/**
 * Sets both pool sizes atomically. Can be called at runtime after initialization.
 * 
 * @param poolSize The new pool size (sets both min and max)
 */
public void setPoolSize(int poolSize) {
    this.minPoolSize = poolSize;
    this.maxPoolSize = poolSize;
    if (connectionPool != null) {
        connectionPool.setPoolSizeRange(poolSize, poolSize);
    }
}

/**
 * Sets pool size range atomically. New method for runtime updates.
 * 
 * @param minPoolSize The new minimum pool size
 * @param maxPoolSize The new maximum pool size
 */
public void setPoolSizeRange(int minPoolSize, int maxPoolSize) {
    this.minPoolSize = minPoolSize;
    this.maxPoolSize = maxPoolSize;
    if (connectionPool != null) {
        connectionPool.setPoolSizeRange(minPoolSize, maxPoolSize);
    }
}
```

**New Getters for Current Pool Sizes:**
```java
/**
 * Gets the current minimum pool size (may have been updated at runtime).
 */
public int getCurrentMinPoolSize() {
    if (connectionPool != null) {
        return connectionPool.getMinPoolSize();
    }
    return minPoolSize;
}

/**
 * Gets the current maximum pool size (may have been updated at runtime).
 */
public int getCurrentMaxPoolSize() {
    if (connectionPool != null) {
        return connectionPool.getMaxPoolSize();
    }
    return maxPoolSize;
}
```

#### 3. JMS Connection Pool

Similar changes needed in:
- `com.atomikos.jms.internal.AbstractJmsConnectionFactoryBean` (if it exists)
- JMS-specific pool implementations

### Testing Strategy

#### Unit Tests

1. **Test Dynamic Growth**
   - Start pool with min=1, max=5
   - Update to min=3 at runtime
   - Verify pool grows to 3 connections

2. **Test Dynamic Shrinking**
   - Start pool with min=5, max=10, actually using 8 connections
   - Update to max=5 at runtime
   - Return 3 connections, verify they get closed
   - Verify 5 remain

3. **Test Graceful Shrinking**
   - Start pool with 10 active connections
   - Update max to 5
   - Verify no connections are forcefully closed
   - Return connections one by one, verify excess are closed

4. **Test Validation**
   - Attempt to set minPoolSize > maxPoolSize (should fail)
   - Attempt to set minPoolSize < 0 (should fail)
   - Attempt to set maxPoolSize < 1 (should fail)

5. **Test Concurrent Updates**
   - Multiple threads updating pool sizes simultaneously
   - Verify thread safety and consistency

6. **Test Edge Cases**
   - Set min=max=0 (should fail, max must be >= 1)
   - Set min=max=100 in small pool
   - Rapid consecutive updates

#### Integration Tests

1. **Test with Real Database**
   - Configure AtomikosDataSourceBean
   - Perform transactions
   - Update pool sizes during active use
   - Verify no connection leaks or errors

2. **Test with JMS**
   - Similar tests with JMS connection pools

3. **Test Maintenance Cycle Interaction**
   - Verify maxIdleTime still works
   - Verify maxLifetime still works
   - Verify dynamic sizing doesn't interfere

## Concerns and Questions

### 1. Maintenance Timer Notification

**Concern:** The maintenance cycle runs on a fixed schedule. When pool size is updated, should we:
- A) Wait for next scheduled cycle (simpler, eventual consistency)
- B) Trigger immediate maintenance cycle (faster reaction, more complex)

**Recommendation:** Start with option A for simplicity. The requirement states "the reaction does not need to be immediate but eventually it should obey by the new settings."

### 2. Connection Pool Subclasses

**Question:** Both `ConnectionPoolWithSynchronizedValidation` and `ConnectionPoolWithConcurrentValidation` extend ConnectionPool. Do they need special handling?

**Answer:** No special handling needed. They override `borrowConnection()` and `retrieveFirstAvailableConnection()` but these already use the base class's pool size management methods.

### 3. Aggressive Shrinking Timing

**Concern:** When maxPoolSize is reduced below current pool size, how aggressively should we shrink?

**Options:**
- Wait for maxIdleTime to expire (respects existing configuration)
- Immediately close available connections exceeding new max (faster convergence)
- Hybrid: immediate shrink to new max, but only if connections are idle

**Recommendation:** Hybrid approach - immediately close idle connections exceeding new max, but respect connections in use.

### 4. JMX/Monitoring Integration

**Question:** Should dynamic pool sizes be exposed via JMX for monitoring?

**Answer:** Out of scope for this feature but would be valuable addition. Current pool sizes can be queried via `poolTotalSize()` and `poolAvailableSize()`.

### 5. Spring Boot Integration

**Question:** Spring Boot actuator integration for pool metrics?

**Answer:** The existing `AtomikosDataSourcePoolMetadataProvidersConfiguration` may need updates to reflect current pool sizes vs configured sizes. This is a separate concern.

### 6. Persistence of Runtime Changes

**Question:** Should runtime pool size changes be persisted?

**Answer:** No. Runtime changes are ephemeral. If the application restarts, it should use the configured (original) values. This is consistent with most connection pool implementations.

### 7. Logging and Auditing

**Concern:** Pool size changes should be logged for troubleshooting.

**Recommendation:** Log at INFO level when pool sizes are changed:
```
"Pool 'myDataSource': minPoolSize changed from 5 to 10"
"Pool 'myDataSource': maxPoolSize changed from 20 to 15, will shrink gradually"
```

### 8. Deadlock Risk

**Concern:** The `setMinPoolSize()` and maintenance cycle both need synchronization. Could this cause deadlocks?

**Analysis:** 
- `setMinPoolSize()` is synchronized on ConnectionPool instance
- Maintenance cycle alarm listener calls synchronized methods on ConnectionPool instance
- Both acquire the same lock, so no deadlock risk
- However, need to ensure setters don't block for long periods

**Mitigation:** Keep setter methods simple - just update fields and return. Let maintenance cycle do the actual work.

### 9. Backward Compatibility

**Concern:** Existing applications may expect pool sizes to be immutable after initialization.

**Analysis:** 
- No breaking changes - existing behavior unchanged if setters not called
- New functionality is opt-in
- Existing getters in ConnectionPoolProperties interface remain
- New getters on ConnectionPool are internal implementation

**Conclusion:** Fully backward compatible.

### 10. Transaction Coordinator Interaction

**Question:** Does changing pool size affect transaction recovery or coordination?

**Answer:** No. Individual connections maintain their transaction state. Pool size changes only affect connection lifecycle, not transaction semantics.

## Implementation Plan

### Phase 1: Core Implementation
1. Add dynamic size fields to ConnectionPool
2. Add setter methods with validation
3. Update maintenance cycle to use dynamic sizes
4. Update canGrow() to use dynamic max
5. Add logging for pool size changes

### Phase 2: DataSource Integration
1. Modify AbstractDataSourceBean setters
2. Add setPoolSizeRange() method
3. Add getCurrentMinPoolSize() and getCurrentMaxPoolSize()
4. Update subclasses if needed

### Phase 3: Testing
1. Create unit tests for ConnectionPool
2. Create integration tests with AtomikosDataSourceBean
3. Create concurrency tests
4. Test with real databases (H2, PostgreSQL)

### Phase 4: Documentation
1. Update JavaDoc
2. Update user documentation
3. Create migration guide
4. Add examples

## Estimated Impact

### Files to Modify
1. `ConnectionPool.java` - ~100 lines added/modified
2. `AbstractDataSourceBean.java` - ~50 lines modified
3. Unit test files - ~300 lines new tests
4. Integration test files - ~200 lines new tests

### Complexity: Medium
- Builds on existing maintenance cycle
- Clear requirements
- Well-defined behavior

### Risk: Low
- No breaking changes
- Leverages existing synchronization
- Incremental implementation possible

## Conclusion

Implementing dynamic connection pool resizing is feasible and can be done with minimal changes to the existing codebase. The key insights are:

1. **Leverage existing maintenance cycle** - Don't reinvent the wheel; the cycle already handles pool size adjustments
2. **Graceful shrinking is built-in** - The existing logic already waits for connections to become available before removing them
3. **Thread safety is preserved** - Existing synchronization mechanisms are sufficient
4. **Backward compatible** - No breaking changes required

The main work involves:
- Adding volatile fields for current min/max sizes
- Adding validated setter methods
- Updating size-checking logic to use current values
- Adding comprehensive tests

This approach aligns with the requirement that "the reaction does not need to be immediate but eventually it should obey by the new settings."
