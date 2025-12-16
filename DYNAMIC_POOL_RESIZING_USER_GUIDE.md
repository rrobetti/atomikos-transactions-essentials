# Dynamic Connection Pool Resizing - User Guide

## Overview

Atomikos connection pools now support runtime (dynamic) resizing of `minPoolSize` and `maxPoolSize` without requiring application restart. This feature enables:

- Right-sizing pools during runtime based on actual load
- Reacting to traffic patterns by adjusting pool sizes dynamically  
- Optimizing resource usage by shrinking pools during low-traffic periods
- Troubleshooting by temporarily increasing pool sizes
- Supporting auto-scaling infrastructure

## Key Features

✅ **Virtual Thread Compatible**: Uses `ReentrantLock` instead of `synchronized`  
✅ **Graceful Shrinking**: Never forcefully closes active connections  
✅ **Thread-Safe**: All operations are atomic and thread-safe  
✅ **Rollback Support**: Can reset to originally configured values  
✅ **Comprehensive Logging**: All changes logged at INFO level  
✅ **Backward Compatible**: Existing code works unchanged  

## Basic Usage

### Initial Configuration

```java
AtomikosDataSourceBean dataSource = new AtomikosDataSourceBean();
dataSource.setUniqueResourceName("myDB");
dataSource.setXaDataSourceClassName("org.postgresql.xa.PGXADataSource");
dataSource.setMinPoolSize(5);   // Initial minimum
dataSource.setMaxPoolSize(10);  // Initial maximum
dataSource.init();
```

### Runtime Pool Size Adjustments

```java
// Increase minimum pool size
dataSource.setMinPoolSize(10);

// Increase maximum pool size
dataSource.setMaxPoolSize(20);

// Update both atomically
dataSource.setPoolSizeRange(15, 25);
```

### Query Current Pool Sizes

```java
// Get current (runtime) pool sizes
int currentMin = dataSource.getMinPoolSize();
int currentMax = dataSource.getMaxPoolSize();

// Get originally configured sizes
int configuredMin = dataSource.getConfiguredMinPoolSize();
int configuredMax = dataSource.getConfiguredMaxPoolSize();

// Get actual pool usage
int total = dataSource.poolTotalSize();
int available = dataSource.poolAvailableSize();
int active = total - available;
```

### Reset to Configured Values

```java
// Rollback to originally configured sizes
dataSource.resetPoolSizes();

System.out.println(dataSource.getMinPoolSize());  // Back to 5
System.out.println(dataSource.getMaxPoolSize());  // Back to 10
```

## Advanced Usage

### Atomic Range Updates

When you need to change both min and max in a way that would violate constraints if done sequentially:

```java
// Current: min=5, max=10
// Want: min=15, max=20

// This would fail:
// dataSource.setMinPoolSize(15);  // ERROR: 15 > current max (10)

// This works:
dataSource.setPoolSizeRange(15, 20);  // Atomic update
```

### Monitoring and Metrics

```java
// Expose pool metrics
public PoolMetrics getPoolMetrics() {
    return new PoolMetrics(
        dataSource.getMinPoolSize(),
        dataSource.getMaxPoolSize(),
        dataSource.getConfiguredMinPoolSize(),
        dataSource.getConfiguredMaxPoolSize(),
        dataSource.poolTotalSize(),
        dataSource.poolAvailableSize()
    );
}
```

### Auto-Scaling Integration

```java
public class PoolAutoScaler {
    
    private final AtomikosDataSourceBean dataSource;
    private final int maxAllowed = 50;
    private final int minAllowed = 2;
    
    public void scaleBasedOnLoad(double cpuUsage, double memoryUsage) {
        int currentMax = dataSource.getMaxPoolSize();
        
        if (cpuUsage > 80 && currentMax < maxAllowed) {
            // Scale up
            int newMax = Math.min(currentMax + 5, maxAllowed);
            dataSource.setMaxPoolSize(newMax);
            logger.info("Scaled pool up to maxPoolSize=" + newMax);
        }
        else if (cpuUsage < 30 && currentMax > minAllowed) {
            // Scale down
            int newMax = Math.max(currentMax - 5, minAllowed);
            dataSource.setMaxPoolSize(newMax);
            logger.info("Scaled pool down to maxPoolSize=" + newMax);
        }
    }
}
```

### JMX Integration (Custom)

```java
public interface PoolManagerMBean {
    int getMinPoolSize();
    void setMinPoolSize(int size);
    int getMaxPoolSize();
    void setMaxPoolSize(int size);
    void resetPoolSizes();
    int getTotalConnections();
    int getAvailableConnections();
}

public class PoolManager implements PoolManagerMBean {
    private final AtomikosDataSourceBean dataSource;
    
    public PoolManager(AtomikosDataSourceBean dataSource) {
        this.dataSource = dataSource;
    }
    
    @Override
    public int getMinPoolSize() {
        return dataSource.getMinPoolSize();
    }
    
    @Override
    public void setMinPoolSize(int size) {
        dataSource.setMinPoolSize(size);
    }
    
    @Override
    public int getMaxPoolSize() {
        return dataSource.getMaxPoolSize();
    }
    
    @Override
    public void setMaxPoolSize(int size) {
        dataSource.setMaxPoolSize(size);
    }
    
    @Override
    public void resetPoolSizes() {
        dataSource.resetPoolSizes();
    }
    
    @Override
    public int getTotalConnections() {
        return dataSource.poolTotalSize();
    }
    
    @Override
    public int getAvailableConnections() {
        return dataSource.poolAvailableSize();
    }
}

// Register with JMX
MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
ObjectName name = new ObjectName("com.example:type=PoolManager,name=myDB");
PoolManager mbean = new PoolManager(dataSource);
mbs.registerMBean(mbean, name);
```

## Behavior Details

### Eventual Consistency

Pool size changes are not immediate - they take effect during the next maintenance cycle (default 60 seconds):

- **Growing**: New connections added during next maintenance cycle
- **Shrinking**: Excess idle connections closed during next maintenance cycle
- **Active connections**: Never forcefully closed, even when shrinking

### Graceful Shrinking Example

```
Current state: maxPoolSize=10, totalConnections=10 (8 active, 2 idle)

Action: dataSource.setMaxPoolSize(7);

Immediate result:
- 2 idle connections closed immediately
- 8 active connections remain (not forcefully closed)

Over time:
- As connections are returned, excess ones are closed
- Eventually pool shrinks to 7 connections
```

### Validation Rules

All pool size changes are validated:

```java
// Valid changes
dataSource.setMinPoolSize(0);    // OK: min can be 0
dataSource.setMaxPoolSize(100);  // OK: max >= 1

// Invalid changes (throw IllegalArgumentException)
dataSource.setMinPoolSize(-1);   // ERROR: min must be >= 0
dataSource.setMaxPoolSize(0);    // ERROR: max must be >= 1
dataSource.setMinPoolSize(20);   // ERROR: min > current max (10)
dataSource.setMaxPoolSize(3);    // ERROR: max < current min (5)
```

### Logging

All pool size changes are logged at INFO level:

```
INFO: atomikos connection pool 'myDB': minPoolSize changed from 5 to 10
INFO: atomikos connection pool 'myDB': maxPoolSize changed from 10 to 20
INFO: atomikos connection pool 'myDB': maxPoolSize reduced below current size (15 connections), will shrink gradually
INFO: atomikos connection pool 'myDB': pool sizes reset to configured values: [5,10] (was [10,20])
```

## Best Practices

### 1. Use Atomic Updates When Needed

```java
// Instead of:
dataSource.setMaxPoolSize(30);
dataSource.setMinPoolSize(15);

// Use atomic update:
dataSource.setPoolSizeRange(15, 30);
```

### 2. Monitor Before Adjusting

```java
public void adjustPoolIfNeeded() {
    int total = dataSource.poolTotalSize();
    int available = dataSource.poolAvailableSize();
    int active = total - available;
    double utilization = (double) active / total;
    
    if (utilization > 0.9) {
        // Pool is heavily used, consider increasing max
        dataSource.setMaxPoolSize(dataSource.getMaxPoolSize() + 5);
    }
}
```

### 3. Set Reasonable Limits

```java
public void setPoolSize(int min, int max) {
    // Enforce organizational limits
    if (max > 100) {
        throw new IllegalArgumentException("maxPoolSize cannot exceed 100");
    }
    if (min < 1) {
        throw new IllegalArgumentException("minPoolSize must be at least 1");
    }
    
    dataSource.setPoolSizeRange(min, max);
}
```

### 4. Use Rollback for Experimentation

```java
// Save current sizes
int originalMin = dataSource.getConfiguredMinPoolSize();
int originalMax = dataSource.getConfiguredMaxPoolSize();

try {
    // Experiment with larger pool
    dataSource.setPoolSizeRange(20, 40);
    
    // Run load test...
    
} finally {
    // Rollback to original
    dataSource.resetPoolSizes();
}
```

### 5. Coordinate with Maintenance Interval

If you need faster reaction times, adjust the maintenance interval:

```java
dataSource.setMaintenanceInterval(30);  // Check every 30 seconds instead of 60
```

## Troubleshooting

### Pool Not Growing

**Symptom**: Set minPoolSize higher but pool size doesn't increase

**Cause**: Maintenance cycle hasn't run yet

**Solution**: Wait for next maintenance cycle (default 60s) or trigger manually via connection borrow

### Pool Not Shrinking

**Symptom**: Set maxPoolSize lower but pool size doesn't decrease

**Cause**: Connections are still in active use

**Solution**: Wait for connections to be returned. Check active connections:

```java
int active = dataSource.poolTotalSize() - dataSource.poolAvailableSize();
System.out.println("Active connections: " + active);
```

### IllegalArgumentException on Size Update

**Symptom**: Exception thrown when calling setMinPoolSize() or setMaxPoolSize()

**Cause**: New value violates constraints (min > max or vice versa)

**Solution**: Use atomic update:

```java
dataSource.setPoolSizeRange(newMin, newMax);
```

### Virtual Thread Issues

**Symptom**: Thread pinning warnings with virtual threads

**Solution**: This implementation uses `ReentrantLock` instead of `synchronized`, so no pinning should occur. If you still see issues, check that you're using a recent JDK version with proper virtual thread support.

## Performance Considerations

### Lock Contention

The implementation uses a single `ReentrantLock` to protect pool state. Under very high concurrency:

- Connection borrowing acquires the lock briefly
- Pool size updates acquire the lock briefly  
- Maintenance cycle acquires the lock during cleanup

In practice, lock contention is minimal because:
- Lock is held for very short periods
- Most operations are reads (using AtomicInteger)
- Virtual threads don't pin the carrier thread

### Memory Overhead

- **AtomicInteger** (4 fields): ~16 bytes
- **ReentrantLock**: ~24 bytes
- **Total overhead**: ~40 bytes per pool (negligible)

### CPU Overhead

- Reading current pool sizes: Lock-free (AtomicInteger.get())
- Updating pool sizes: One lock acquisition
- Maintenance cycle: Same as before

**Impact**: < 1% overhead in typical scenarios

## Migration Guide

### From Static Pool Sizes

**Before:**
```java
dataSource.setMinPoolSize(5);
dataSource.setMaxPoolSize(10);
dataSource.init();

// No runtime changes possible
```

**After:**
```java
dataSource.setMinPoolSize(5);
dataSource.setMaxPoolSize(10);
dataSource.init();

// Runtime changes now possible
dataSource.setMaxPoolSize(20);  // Adjust based on load
```

**No code changes required** - existing code continues to work.

### Backward Compatibility

✅ All existing methods work unchanged  
✅ No breaking changes to ConnectionPoolProperties interface  
✅ New methods are additions only  
✅ Default behavior preserved  

## FAQ

**Q: Can I call setMinPoolSize() before init()?**  
A: Yes, it works both before and after init(). Before init(), it sets the initial value. After init(), it updates the runtime value.

**Q: What happens if I set minPoolSize > current connections?**  
A: The pool will grow to meet the new minimum during the next maintenance cycle.

**Q: What happens if I set maxPoolSize < current connections?**  
A: The pool will shrink gradually as connections are returned. Active connections are never forcefully closed.

**Q: Are pool size changes persisted?**  
A: No, changes are ephemeral. After restart, the pool uses the originally configured values.

**Q: Can I query the original configured values?**  
A: Yes, use `getConfiguredMinPoolSize()` and `getConfiguredMaxPoolSize()`.

**Q: Is this thread-safe?**  
A: Yes, all operations are protected by `ReentrantLock` and use `AtomicInteger` for lock-free reads.

**Q: Does this work with JMS pools?**  
A: Yes, if the JMS pool uses the same ConnectionPool base class.

**Q: How fast do changes take effect?**  
A: Within one maintenance cycle (default 60 seconds). Not immediate, but eventual.

## See Also

- ConnectionPool API Documentation
- AbstractDataSourceBean API Documentation  
- Atomikos Transaction Manager Configuration Guide
- Virtual Thread Best Practices

## Support

For questions or issues:
- GitHub Issues: https://github.com/atomikos/transactions-essentials
- Documentation: https://www.atomikos.com/Documentation/
- Community Forum: https://www.atomikos.com/Forums/
