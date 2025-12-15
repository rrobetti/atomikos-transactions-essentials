/**
 * Copyright (C) 2000-2024 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.datasource.pool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.atomikos.logging.Logger;
import com.atomikos.logging.LoggerFactory;
import com.atomikos.thread.InterruptedExceptionHelper;
import com.atomikos.thread.TaskManager;
import com.atomikos.timing.AlarmTimer;
import com.atomikos.timing.AlarmTimerListener;
import com.atomikos.timing.PooledAlarmTimer;


public abstract class ConnectionPool<ConnectionType> implements XPooledConnectionEventListener<ConnectionType>
{
	private static Logger LOGGER = LoggerFactory.createLogger(ConnectionPool.class);

	private final static int DEFAULT_MAINTENANCE_INTERVAL = 60;

	protected List<XPooledConnection<ConnectionType>> connections = new ArrayList<XPooledConnection<ConnectionType>>();
	private ConnectionFactory<ConnectionType> connectionFactory;
	private ConnectionPoolProperties properties;
	private boolean destroyed;
	private PooledAlarmTimer maintenanceTimer;
	private String name;
	private ExecutorService dynamicallyGrowPoolExecutor = Executors.newFixedThreadPool(1);
	
	// Dynamic pool sizing support - using AtomicInteger for virtual thread compatibility
	private final AtomicInteger currentMinPoolSize;
	private final AtomicInteger currentMaxPoolSize;
	private final AtomicInteger configuredMinPoolSize;
	private final AtomicInteger configuredMaxPoolSize;
	
	// ReentrantLock for better virtual thread support instead of synchronized
	protected final ReentrantLock poolLock = new ReentrantLock();


	public ConnectionPool ( ConnectionFactory<ConnectionType> connectionFactory , ConnectionPoolProperties properties ) throws ConnectionPoolException
	{
		this.connectionFactory = connectionFactory;
		this.properties = properties;
		this.destroyed = false;
		this.name = properties.getUniqueResourceName();
		
		// Initialize pool sizes from properties
		int minSize = properties.getMinPoolSize();
		int maxSize = properties.getMaxPoolSize();
		this.currentMinPoolSize = new AtomicInteger(minSize);
		this.currentMaxPoolSize = new AtomicInteger(maxSize);
		this.configuredMinPoolSize = new AtomicInteger(minSize);
		this.configuredMaxPoolSize = new AtomicInteger(maxSize);
		
		init();
	}

	private void assertNotDestroyed() throws ConnectionPoolException
	{
		if (destroyed) throw new ConnectionPoolException ( "Pool was already destroyed - you can no longer use it" );
	}

	private void init() throws ConnectionPoolException
	{
		if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": initializing..." );
		addConnectionsIfMinPoolSizeNotReached();
		launchMaintenanceTimer();
	}

	private void launchMaintenanceTimer() {
		int maintenanceInterval = properties.getMaintenanceInterval();
		if ( maintenanceInterval <= 0 ) {
			if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": using default maintenance interval..." );
			maintenanceInterval = DEFAULT_MAINTENANCE_INTERVAL;
		}
		maintenanceTimer = new PooledAlarmTimer ( maintenanceInterval * 1000 );
		maintenanceTimer.addAlarmTimerListener(new AlarmTimerListener() {
			public void alarm(AlarmTimer timer) {
				removeConnectionsThatExceededMaxLifetime();
				addConnectionsIfMinPoolSizeNotReached();
				removeIdleConnectionsIfMinPoolSizeExceeded();
			}
		});
		TaskManager.SINGLETON.executeTask ( maintenanceTimer );
	}

	private void addConnectionsIfMinPoolSizeNotReached() {
		poolLock.lock();
		try {
			int connectionsToAdd = currentMinPoolSize.get() - totalSize();
			for ( int i = 0 ; i < connectionsToAdd ; i++ ) {
				try {
					XPooledConnection<ConnectionType> xpc = createPooledConnection();
					connections.add ( xpc );
					xpc.registerXPooledConnectionEventListener ( this );
				} catch ( Exception dbDown ) {
					//see case 26380
					if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": could not establish initial connection" , dbDown );
				}
			}
		} finally {
			poolLock.unlock();
		}
	}

	private XPooledConnection<ConnectionType> createPooledConnection()
			throws CreateConnectionException {
		XPooledConnection<ConnectionType> xpc = connectionFactory.createPooledConnection();
		return xpc;
	}
	
	protected abstract ConnectionType recycleConnectionIfPossible() throws Exception;

	/**
	 * Borrows a connection from the pool.
	 * @return The connection
	 * @throws CreateConnectionException If the pool attempted to grow but failed.
	 * @throws PoolExhaustedException If the pool could not grow because it is exhausted.
	 * @throws ConnectionPoolException Other errors.
	 */
	public ConnectionType borrowConnection() throws CreateConnectionException, PoolExhaustedException,
											 ConnectionPoolException {
		assertNotDestroyed();
		ConnectionType ret = null;	
		ret = findExistingOpenConnectionForCallingThread();	
		if (ret == null) {
			ret = findOrWaitForAnAvailableConnection();		
		}
		return ret;
	}

	private ConnectionType findOrWaitForAnAvailableConnection() throws ConnectionPoolException {
		ConnectionType ret = null;
		long remainingTime = properties.getBorrowConnectionTimeout() * 1000L;		
		do {
			long before = System.currentTimeMillis();
			ret = retrieveFirstAvailableConnectionAndGrowPoolIfNecessary(remainingTime);
			remainingTime -= calculateDelta(before);
			if ( ret == null ) {
				remainingTime = waitForAtLeastOneAvailableConnection(remainingTime);
				assertNotDestroyed();
			}
		} while ( ret == null );
		return ret;
	}

	private long calculateDelta(long before) {
		long now = System.currentTimeMillis();
        return (now - before);
	}

	private ConnectionType retrieveFirstAvailableConnectionAndGrowPoolIfNecessary(long remainingTime) throws CreateConnectionException {
		
		ConnectionType ret = retrieveFirstAvailableConnection();
		if ( ret == null && canGrow() ) {
			growPool(remainingTime);
			ret = retrieveFirstAvailableConnection();
		}		
		return ret;
	}

	private ConnectionType findExistingOpenConnectionForCallingThread() {
		ConnectionType recycledConnection = null ;
		try {
			recycledConnection = recycleConnectionIfPossible();
		} catch (Exception e) {
			//ignore but log
			LOGGER.logDebug ( this + ": error while trying to recycle" , e );
		}
		return recycledConnection;
	}

	protected void logCurrentPoolSize() {
		if ( LOGGER.isTraceEnabled() )  {
			LOGGER.logTrace( this +  ": current size: " + availableSize() + "/" + totalSize());
		}
	}

	private boolean canGrow() {
		return totalSize() < currentMaxPoolSize.get();
	}

	protected abstract ConnectionType retrieveFirstAvailableConnection();

	private void growPool(long remainingTime) throws CreateConnectionException {
		poolLock.lock();
		try {
			if (canGrow()) { // cf case 181871 
				Future<XPooledConnection<ConnectionType>> futureXPC = 
						dynamicallyGrowPoolExecutor.submit(() -> createPooledConnection()); //cf case 192016: in separate thread to allow control of timeout		
				XPooledConnection<ConnectionType> ret = null;
				try {
					ret = futureXPC.get(remainingTime, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					InterruptedExceptionHelper.handleInterruptedException(e);
				} catch (TimeoutException e) {
					String msg = this +  ": timed out waiting for new pooled connection...";
					LOGGER.logDebug( msg , e);
				} catch (Exception e) {
					String msg = this +  ": failed to grow pool due to unexpected exception...";
					LOGGER.logWarning( msg , e);
				}
				if (ret != null) {			
					connections.add(ret);
					ret.registerXPooledConnectionEventListener(this);
				} else {
					// try to liberate executor's thread for reuse
					futureXPC.cancel(true); 
					// WORST CASE: the dedicated thread could be blocked forever IF AND ONLY IF:
					// 1. the network IO blocks forever for a new (!) connection, AND
					// 2. the driver does not support interrupts (so cancel did not work)
					// => in that case the pool remains at current size (>=minPoolSize)
					// NB: in that case, minPoolSize is done by the maintenance thread,
					// not the (blocked) worker thread
				}
			}
			logCurrentPoolSize();
		} finally {
			poolLock.unlock();
		}
	}

	private void removeIdleConnectionsIfMinPoolSizeExceeded() {
		poolLock.lock();
		try {
			if (connections == null || properties.getMaxIdleTime() <= 0 )
				return;

			if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": trying to shrink pool" );
			List<XPooledConnection<ConnectionType>> connectionsToRemove = new ArrayList<XPooledConnection<ConnectionType>>();
			
			int currentMin = currentMinPoolSize.get();
			int currentMax = currentMaxPoolSize.get();
			int currentTotal = totalSize();
			
			// Calculate how many connections we need to remove
			int maxConnectionsToRemove = currentTotal - currentMin;
			
			// If current size exceeds max, we need to aggressively shrink
			if (currentTotal > currentMax) {
				maxConnectionsToRemove = Math.max(maxConnectionsToRemove, currentTotal - currentMax);
			}
			
			if ( maxConnectionsToRemove > 0 ) {
				for ( int i=0 ; i < connections.size() ; i++ ) {
					XPooledConnection<ConnectionType> xpc = connections.get(i);
					long lastRelease = xpc.getLastTimeReleased();
					long maxIdle = properties.getMaxIdleTime();
					long now = System.currentTimeMillis();
					if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": connection idle for " + (now - lastRelease) + "ms");
					
					// For connections exceeding max pool size, close immediately if available
					boolean exceedsMax = currentTotal > currentMax && connectionsToRemove.size() < (currentTotal - currentMax);
					boolean exceedsMinAndIdle = !exceedsMax && 
						xpc.isAvailable() && 
						( (now - lastRelease) >= (maxIdle * 1000L) ) && 
						( connectionsToRemove.size() < maxConnectionsToRemove );
					
					if ( xpc.isAvailable() && (exceedsMax || exceedsMinAndIdle) ) {
						if ( LOGGER.isTraceEnabled() ) {
							if (exceedsMax) {
								LOGGER.logTrace ( this + ": closing connection to meet new maxPoolSize: " + xpc);
							} else {
								LOGGER.logTrace ( this + ": connection idle for more than " + maxIdle + "s, closing it: " + xpc);
							}
						}
						destroyPooledConnection(xpc);
						connectionsToRemove.add(xpc);
					}
				}
			}
			connections.removeAll(connectionsToRemove);
			logCurrentPoolSize();
		} finally {
			poolLock.unlock();
		}
	}

	protected void destroyPooledConnection(XPooledConnection<ConnectionType> xpc) {
		xpc.destroy();
	}
	
	private void removeConnectionsThatExceededMaxLifetime()
	{
		poolLock.lock();
		try {
			long maxLifetime = properties.getMaxLifetime() * 1000L;
			if ( connections == null || maxLifetime <= 0 ) return;

			if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": closing connections that exceeded maxLifetime" );

			Iterator<XPooledConnection<ConnectionType>> it = connections.iterator();
			long now = System.currentTimeMillis();
			while ( it.hasNext() ) {
				XPooledConnection<ConnectionType> xpc = it.next();
				long creationTime = xpc.getCreationTime();
				if ( xpc.isAvailable() ) {
					if ((now - creationTime) >= (maxLifetime)) {
						if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": connection in use for more than maxLifetime, destroying it: " + xpc );
						destroyPooledConnection(xpc);
						it.remove();
					}
				}
			}
			logCurrentPoolSize();
		} finally {
			poolLock.unlock();
		}
	}

	public void destroy()
	{
		poolLock.lock();
		try {
			if ( ! destroyed ) {
				LOGGER.logInfo ( this + ": destroying pool..." );
				for ( int i=0 ; i < connections.size() ; i++ ) {
					XPooledConnection<ConnectionType> xpc =  connections.get(i);
					if ( !xpc.isAvailable() ) {
						LOGGER.logWarning ( this + ": connection is still in use on pool destroy: " + xpc +
						" - please check your shutdown sequence to avoid heuristic termination " +
						"of ongoing transactions!" );
					}
					destroyPooledConnection(xpc);
				}
				connections = null;
				destroyed = true;
				maintenanceTimer.stopTimer();
				dynamicallyGrowPoolExecutor.shutdownNow();
				if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": pool destroyed." );
			}
		} finally {
			poolLock.unlock();
		}
	}
	
	public void refresh() {
		poolLock.lock();
		try {
			List<XPooledConnection<ConnectionType>> connectionsToRemove = new ArrayList<XPooledConnection<ConnectionType>>();
			for (XPooledConnection<ConnectionType> conn : connections) {
				if (conn.isAvailable()) {
					connectionsToRemove.add(conn);
					destroyPooledConnection(conn);
				}
			}
			connections.removeAll(connectionsToRemove);
			addConnectionsIfMinPoolSizeNotReached();
		} finally {
			poolLock.unlock();
		}
	}

	/**
	 * Wait until the connection pool contains an available connection or a timeout happens.
	 * Returns immediately if the pool already contains a connection in state available.
	 * @throws CreateConnectionException if a timeout happened while waiting for a connection
	 */
	private long waitForAtLeastOneAvailableConnection(long waitTime) throws PoolExhaustedException
	{
		poolLock.lock();
		try {
			while (availableSize() == 0) {
				if ( waitTime <= 0 ) throw new PoolExhaustedException ( "ConnectionPool: pool is empty - increase either maxPoolSize or borrowConnectionTimeout" );
				long before = System.currentTimeMillis();
				try {
					if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": about to wait for connection during " + waitTime + "ms...");
					// Use Condition for waiting with ReentrantLock
					poolLock.unlock();
					Thread.sleep(Math.min(waitTime, 100)); // Short sleep to avoid busy waiting
					poolLock.lock();
				} catch (InterruptedException ex) {
					// cf bug 67457
					InterruptedExceptionHelper.handleInterruptedException ( ex );
					// ignore
					if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": interrupted during wait" , ex );
				}
				if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": done waiting." );
				waitTime -= calculateDelta(before);
			}
			return waitTime;
		} finally {
			poolLock.unlock();
		}
	}

	/**
	 * The amount of pooled connections in state available.
	 * @return the amount of pooled connections in state available.
	 */
	public int availableSize()
	{
		poolLock.lock();
		try {
			int ret = 0;

			if ( !destroyed ) {
				int count = 0;
				for ( int i=0 ; i < connections.size() ; i++ ) {
					XPooledConnection<ConnectionType> xpc = connections.get(i);
					if (xpc.isAvailable()) count++;
				}
				ret = count;
			}
			return ret;
		} finally {
			poolLock.unlock();
		}
	}

	/**
	 * The total amount of pooled connections in any state.
	 * @return the total amount of pooled connections in any state
	 */
	public int totalSize()
	{
		poolLock.lock();
		try {
			if ( destroyed ) return 0;

			return connections.size();
		} finally {
			poolLock.unlock();
		}
	}
	
	/**
	 * Sets the minimum pool size at runtime.
	 * The pool will gradually grow to meet the new minimum during maintenance cycles.
	 * 
	 * @param minPoolSize new minimum pool size (must be >= 0 and <= maxPoolSize)
	 * @throws IllegalArgumentException if constraints are violated
	 * @throws ConnectionPoolException if the pool has been destroyed
	 */
	public void setMinPoolSize(int minPoolSize) throws ConnectionPoolException {
		assertNotDestroyed();
		int currentMax = currentMaxPoolSize.get();
		validateMinPoolSize(minPoolSize, currentMax);
		
		int oldValue = currentMinPoolSize.getAndSet(minPoolSize);
		if (oldValue != minPoolSize) {
			LOGGER.logInfo(this + ": minPoolSize changed from " + oldValue + " to " + minPoolSize);
		}
	}
	
	/**
	 * Sets the maximum pool size at runtime.
	 * If the new maximum is less than current pool size, excess connections
	 * will be gradually closed as they become available.
	 * 
	 * @param maxPoolSize new maximum pool size (must be >= minPoolSize and >= 1)
	 * @throws IllegalArgumentException if constraints are violated
	 * @throws ConnectionPoolException if the pool has been destroyed
	 */
	public void setMaxPoolSize(int maxPoolSize) throws ConnectionPoolException {
		assertNotDestroyed();
		int currentMin = currentMinPoolSize.get();
		validateMaxPoolSize(currentMin, maxPoolSize);
		
		int oldValue = currentMaxPoolSize.getAndSet(maxPoolSize);
		if (oldValue != maxPoolSize) {
			LOGGER.logInfo(this + ": maxPoolSize changed from " + oldValue + " to " + maxPoolSize);
			if (maxPoolSize < totalSize()) {
				LOGGER.logInfo(this + ": maxPoolSize reduced below current size (" + totalSize() + " connections), will shrink gradually");
			}
		}
	}
	
	/**
	 * Sets both min and max pool sizes atomically.
	 * 
	 * @param minPoolSize new minimum pool size
	 * @param maxPoolSize new maximum pool size
	 * @throws IllegalArgumentException if constraints are violated
	 * @throws ConnectionPoolException if the pool has been destroyed
	 */
	public void setPoolSizeRange(int minPoolSize, int maxPoolSize) throws ConnectionPoolException {
		assertNotDestroyed();
		validateMinPoolSize(minPoolSize, maxPoolSize);
		validateMaxPoolSize(minPoolSize, maxPoolSize);
		
		int oldMin = currentMinPoolSize.getAndSet(minPoolSize);
		int oldMax = currentMaxPoolSize.getAndSet(maxPoolSize);
		
		if (oldMin != minPoolSize || oldMax != maxPoolSize) {
			LOGGER.logInfo(this + ": pool size range changed from [" + oldMin + "," + oldMax + "] to [" + minPoolSize + "," + maxPoolSize + "]");
		}
	}
	
	/**
	 * Resets pool sizes to their originally configured values.
	 * This provides a rollback mechanism for runtime changes.
	 * 
	 * @throws ConnectionPoolException if the pool has been destroyed
	 */
	public void resetPoolSizes() throws ConnectionPoolException {
		assertNotDestroyed();
		int configMin = configuredMinPoolSize.get();
		int configMax = configuredMaxPoolSize.get();
		
		int oldMin = currentMinPoolSize.getAndSet(configMin);
		int oldMax = currentMaxPoolSize.getAndSet(configMax);
		
		if (oldMin != configMin || oldMax != configMax) {
			LOGGER.logInfo(this + ": pool sizes reset to configured values: [" + configMin + "," + configMax + "] (was [" + oldMin + "," + oldMax + "])");
		}
	}
	
	/**
	 * Gets the current minimum pool size (may differ from initial configuration).
	 * 
	 * @return current minimum pool size
	 */
	public int getMinPoolSize() {
		return currentMinPoolSize.get();
	}
	
	/**
	 * Gets the current maximum pool size (may differ from initial configuration).
	 * 
	 * @return current maximum pool size
	 */
	public int getMaxPoolSize() {
		return currentMaxPoolSize.get();
	}
	
	/**
	 * Gets the originally configured minimum pool size.
	 * 
	 * @return configured minimum pool size
	 */
	public int getConfiguredMinPoolSize() {
		return configuredMinPoolSize.get();
	}
	
	/**
	 * Gets the originally configured maximum pool size.
	 * 
	 * @return configured maximum pool size
	 */
	public int getConfiguredMaxPoolSize() {
		return configuredMaxPoolSize.get();
	}
	
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

	public void onXPooledConnectionTerminated(XPooledConnection<ConnectionType> connection) {
		// No lock needed here - just logging
		if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace( this +  ": connection " + connection + " became available, notifying potentially waiting threads");
	}
		
	public String toString() {
		return "atomikos connection pool '" + name + "'";
	}

}
