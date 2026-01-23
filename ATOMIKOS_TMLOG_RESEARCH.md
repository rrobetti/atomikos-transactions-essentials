# Atomikos Transaction Log (tmlog) Research

This document provides a comprehensive analysis of how Atomikos uses its transaction log (tmlog) file, including transaction states, transitions, and behavior in various scenarios.

## Table of Contents
- [Overview](#overview)
- [When Transactions are Stored to tmlog](#when-transactions-are-stored-to-tmlog)
- [When Transactions are Removed from tmlog](#when-transactions-are-removed-from-tmlog)
- [Transaction States](#transaction-states)
- [Scenario 1: Successful Transaction](#scenario-1-successful-transaction)
- [Scenario 2: Commit Failure After Prepare Success](#scenario-2-commit-failure-after-prepare-success)
- [Scenario 3: Queue Succeeded, Database Failed, No Prepared Transaction](#scenario-3-queue-succeeded-database-failed-no-prepared-transaction)
- [Critical Question: Rollback After Prepare Success?](#critical-question-rollback-after-prepare-success)

---

## Overview

Atomikos uses a transaction log file (tmlog) to ensure ACID properties and enable recovery in case of system failures. The log follows the Two-Phase Commit (2PC) protocol and records transactions in **recoverable states** to ensure proper recovery.

### Key Components

- **StateRecoveryManagerImp**: Listens to FSM (Finite State Machine) state transitions and triggers logging
- **OltpLogImp**: Validates and persists transaction records to the repository
- **RecoveryLogImp**: Manages recovery operations and cleanup
- **RecoveryDomainService**: Performs periodic recovery scans for expired transactions

---

## When Transactions are Stored to tmlog

Transactions are written to the tmlog when they enter **recoverable states**. This happens through the following process:

1. **Trigger**: When a transaction coordinator transitions to a recoverable state
2. **States that trigger logging**:
   - `PREPARING` - Prepare phase in progress
   - `IN_DOUBT` - Waiting for commit/abort decision (most critical for recovery)
   - `COMMITTING` - Commit phase in progress
   - `ABORTING` - Rollback phase in progress
   - `HEUR_COMMITTED` - Heuristically committed
   - `HEUR_ABORTED` - Heuristically rolled back
   - `HEUR_MIXED` - Mixed outcome across participants
   - `HEUR_HAZARD` - Uncertain outcome

3. **Process Flow**:
   ```
   Coordinator enters recoverable state
   → StateRecoveryManagerImp.preEnter() is called
   → PendingTransactionRecord is created from coordinator state
   → OltpLogImp.write() persists the record to repository
   ```

4. **Non-recoverable states** (NOT logged):
   - `ACTIVE` - Transaction is running (not yet prepared)
   - `MARKED_ABORT` - Rollback-only flag set
   - `COMMITTED` - Successfully committed (terminal state, no recovery needed)
   - `ABORTED` - Rolled back (terminal state, no recovery needed)
   - `ABANDONED` - Timed out without resolution

**Implementation References**:
- `StateRecoveryManagerImp.java` (lines 34-51)
- `OltpLogImp.java` (line 48) - Validates recoverable states

---

## When Transactions are Removed from tmlog

Transactions are removed or marked as terminated in two scenarios:

### Scenario A: Normal Termination

When a transaction completes (successfully or with failure):

1. **Trigger**: Transaction reaches a final state
2. **Process**:
   ```
   RecoveryLogImp.forget(coordinatorId) is called
   → Record is marked as TERMINATED state
   → Record is updated in repository (state change, not deletion)
   ```

**Implementation Reference**: `RecoveryLogImp.java` (lines 98-102)

### Scenario B: Recovery Cleanup

During periodic recovery scans:

1. **Expired COMMITTING transactions**: Automatically forgotten after timeout
2. **Expired IN_DOUBT transactions**: Forgotten if beyond `maxTimeout`
3. **Foreign IN_DOUBT coordinators**: Forgotten for heuristic abort scenarios
4. **Process**:
   ```
   RecoveryDomainService performs periodic scans
   → Identifies expired transactions
   → Calls RecoveryLogImp.forgetTransactionRecords()
   → Removes all expired records from repository
   ```

**Implementation References**:
- `RecoveryLogImp.java` (lines 75-83)
- `RecoveryDomainService.java` (recovery logic)

---

## Transaction States

Atomikos defines transaction states in the `TxState` enum:

### Operational States (OLTP)
- **ACTIVE**: Transaction is running, no logging
- **MARKED_ABORT**: Rollback-only flag set
- **LOCALLY_DONE**: Preparation/termination phase
- **COMMITTED**: Successfully committed (terminal)
- **ABORTED**: Rolled back (terminal)
- **ABANDONED**: Timed out without resolution

### Recoverable States (Logged to tmlog)
- **PREPARING**: Prepare phase in progress
- **IN_DOUBT**: Waiting for commit/abort decision (critical!)
- **COMMITTING**: Commit phase in progress
- **ABORTING**: Rollback phase in progress

### Heuristic/Final States
- **HEUR_COMMITTED**: Heuristically committed
- **HEUR_ABORTED**: Heuristically rolled back
- **HEUR_MIXED**: Mixed outcome (some resources committed, others rolled back)
- **HEUR_HAZARD**: Uncertain outcome (need manual intervention)
- **TERMINATED**: Final cleanup marker

### State Transition Rules

From `TxState.java`, these transitions are enforced:

- **ACTIVE** → PREPARING, COMMITTING, ABORTING
- **PREPARING** → IN_DOUBT, ABORTING, TERMINATED, ABANDONED
- **IN_DOUBT** → ABORTING, COMMITTING, ABANDONED, TERMINATED
- **COMMITTING** → HEUR_ABORTED, HEUR_COMMITTED, HEUR_HAZARD, HEUR_MIXED, TERMINATED, ABANDONED
- **ABORTING** → HEUR_ABORTED, HEUR_COMMITTED, HEUR_HAZARD, HEUR_MIXED, TERMINATED, ABANDONED

---

## Scenario 1: Successful Transaction

This diagram shows a typical successful 2PC transaction with two participants (e.g., Database and Queue Manager).

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: Transaction begins
    
    note right of ACTIVE
        Not logged to tmlog
        Transaction is executing
    end note
    
    ACTIVE --> PREPARING: commit() called
    
    note right of PREPARING
        ✓ LOGGED to tmlog
        Coordinator sends prepare()
        to all participants
    end note
    
    PREPARING --> IN_DOUBT: All prepare() succeed
    
    note right of IN_DOUBT
        ✓ LOGGED to tmlog
        Critical recovery state
        All participants are prepared
        Awaiting final decision
    end note
    
    IN_DOUBT --> COMMITTING: Commit decision made
    
    note right of COMMITTING
        ✓ LOGGED to tmlog
        Coordinator sends commit()
        to all participants
    end note
    
    COMMITTING --> TERMINATED: All commit() succeed
    
    note right of TERMINATED
        ✓ LOGGED to tmlog
        Transaction complete
        Record marked for cleanup
    end note
    
    TERMINATED --> [*]: forget() removes from tmlog
    
    note left of TERMINATED
        Record removed during:
        - Normal termination
        - Recovery cleanup
    end note
```

### Detailed Flow for Successful Transaction:

1. **ACTIVE** (not logged): Transaction starts, application executes business logic
2. **PREPARING** (logged): 
   - Transaction manager calls `prepare()` on Database participant → Vote: YES
   - Transaction manager calls `prepare()` on Queue Manager participant → Vote: YES
3. **IN_DOUBT** (logged): 
   - All participants voted YES
   - Coordinator makes COMMIT decision
   - Critical state: if crash occurs here, recovery will commit
4. **COMMITTING** (logged):
   - Coordinator calls `commit()` on Database → SUCCESS
   - Coordinator calls `commit()` on Queue Manager → SUCCESS
5. **TERMINATED** (logged):
   - All resources committed successfully
   - Record marked as TERMINATED
6. **Removed from tmlog**: `forget()` is called, record is removed

---

## Scenario 2: Commit Failure After Prepare Success

This diagram shows what happens when prepare succeeds on all participants, but commit fails on one resource (Database) after succeeding on another (Queue Manager).

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: Transaction begins
    
    note right of ACTIVE
        Not logged to tmlog
        Transaction is executing
    end note
    
    ACTIVE --> PREPARING: commit() called
    
    note right of PREPARING
        ✓ LOGGED to tmlog
        prepare() sent to participants
    end note
    
    PREPARING --> IN_DOUBT: All prepare() succeed
    
    note right of IN_DOUBT
        ✓ LOGGED to tmlog
        Database: prepared ✓
        Queue Manager: prepared ✓
    end note
    
    IN_DOUBT --> COMMITTING: Commit decision made
    
    note right of COMMITTING
        ✓ LOGGED to tmlog
        Queue Manager: commit() ✓
        Database: commit() ✗ FAILS
    end note
    
    COMMITTING --> HEUR_HAZARD: Commit exception on Database
    
    note right of HEUR_HAZARD
        ✓ LOGGED to tmlog
        Heuristic outcome
        Queue Manager: COMMITTED
        Database: UNKNOWN/FAILED
        Retry flag set
    end note
    
    state recovery_fork <<choice>>
    HEUR_HAZARD --> recovery_fork: Recovery attempts
    
    recovery_fork --> HEUR_MIXED: If Database actually<br/>rolled back
    recovery_fork --> TERMINATED: If Database eventually<br/>commits on retry
    recovery_fork --> ABANDONED: If maxTimeout exceeded
    
    note right of HEUR_MIXED
        ✓ LOGGED to tmlog
        Mixed outcome:
        Queue Manager: COMMITTED
        Database: ROLLED BACK
        Manual intervention needed
    end note
    
    HEUR_MIXED --> [*]: Manual resolution<br/>or timeout
    TERMINATED --> [*]: forget() removes<br/>from tmlog
    ABANDONED --> [*]: Expired, removed<br/>from tmlog
```

### Detailed Flow for Failed Commit Scenario:

1. **ACTIVE** (not logged): Transaction starts normally
2. **PREPARING** (logged):
   - `prepare()` on Database → Vote: YES ✓
   - `prepare()` on Queue Manager → Vote: YES ✓
3. **IN_DOUBT** (logged):
   - All participants prepared successfully
   - Coordinator makes COMMIT decision (cannot be changed!)
4. **COMMITTING** (logged):
   - `commit()` on Queue Manager → SUCCESS ✓
   - `commit()` on Database → **EXCEPTION** ✗
   - Exception wrapped as `HeurHazardException` (CommitMessage.java, lines 54-67)
5. **HEUR_HAZARD** (logged):
   - Transaction enters heuristic hazard state
   - Queue Manager has committed (cannot be undone)
   - Database outcome is unknown
   - Retry flag is set for recovery attempts
6. **Recovery Attempts**:
   - Recovery service periodically scans for expired COMMITTING/HEUR_HAZARD transactions
   - Attempts to retry commit on Database
   - Three possible outcomes:
     - **Success** → TERMINATED (Database eventually commits)
     - **Permanent Failure** → HEUR_MIXED (Database rolled back, Queue Manager committed)
     - **Timeout** → ABANDONED (maxTimeout exceeded, needs manual intervention)

---

## Critical Question: Rollback After Prepare Success?

### ❌ NO - Atomikos Will NOT Send a Rollback

**Question**: In the scenario where prepare succeeds but commit fails on the database (after succeeding on queue manager), will Atomikos send a rollback to the database resource after prepare succeeded and commit failed?

**Answer**: **NO, Atomikos will NOT automatically send a rollback to the database.**

### Why No Rollback?

Once a transaction enters the **IN_DOUBT** state (after all participants vote YES during prepare), the coordinator makes an **irrevocable COMMIT decision**. According to the Two-Phase Commit protocol:

1. **Prepare Phase**: All participants vote YES or NO
   - If any participant votes NO → coordinator decides ROLLBACK
   - If all participants vote YES → coordinator decides **COMMIT** (cannot change!)

2. **Commit Phase**: Coordinator executes the decision
   - The decision is **durably logged** (IN_DOUBT → COMMITTING state)
   - The decision cannot be reversed, even if commit fails on some participants

### What Actually Happens?

When commit fails on Database after succeeding on Queue Manager:

1. **No Rollback Sent**: The Database is NOT told to rollback
2. **Heuristic State**: Transaction enters `HEUR_HAZARD` or `HEUR_MIXED` state
3. **Recovery Retries**: Recovery service attempts to retry commit on Database
4. **Possible Outcomes**:
   - **Database eventually commits** → Consistent state reached
   - **Database rolled back** → `HEUR_MIXED` state (inconsistent!)
   - **Database remains in-doubt** → `HEUR_HAZARD` state (manual intervention needed)

### Code Evidence

From `CommitMessage.java` (lines 54-67):
```java
// When commit throws ANY exception (except heuristic exceptions):
// It wraps it as HeurHazardException with retry=true
// Transaction enters HEUR_HAZARD state, NOT rolled back
```

From `TxState.java`:
```java
// Legal transitions from COMMITTING state:
COMMITTING → HEUR_ABORTED, HEUR_COMMITTED, HEUR_HAZARD, HEUR_MIXED, 
             TERMINATED, ABANDONED
// Note: ABORTING is NOT a legal transition from COMMITTING
```

From `IndoubtStateHandler.java` and `HeurHazardStateHandler.java`:
- Recovery logic attempts to **replay the commit decision**
- If Database times out, it may perform **heuristic abort** (Database's decision, not coordinator's)
- Coordinator never sends explicit rollback after IN_DOUBT state

### Summary

Once the coordinator makes a COMMIT decision (after successful prepare), it is **committed to committing**. If a participant fails during commit:

- ✓ Coordinator will **retry commit** during recovery
- ✓ Transaction enters **heuristic state** if outcome is uncertain
- ✗ Coordinator will **NOT send rollback** to prepared participants
- ⚠️ **Inconsistent state possible** (HEUR_MIXED) requiring manual intervention

This behavior ensures that the transaction manager adheres to the Two-Phase Commit protocol's durability guarantees while exposing the inherent risk of heuristic outcomes when commit failures occur.

---

## Implementation References

Key source files analyzed:

- `TxState.java` - State definitions and transition rules
- `StateRecoveryManagerImp.java` - Triggers logging on state entry (lines 34-51)
- `OltpLogImp.java` - Validates and persists records (line 48)
- `RecoveryLogImp.java` - Recovery and cleanup operations (lines 75-102)
- `RecoveryDomainService.java` - Periodic recovery scans
- `CommitMessage.java` - Commit phase implementation (lines 54-67)
- `TerminationResult.java` - Heuristic outcome detection (lines 111-117)
- `HeurHazardStateHandler.java` - Handles HEUR_HAZARD state
- `HeurMixedStateHandler.java` - Handles HEUR_MIXED state
- `IndoubtStateHandler.java` - Handles IN_DOUBT state and timeout

---

## Conclusion

Atomikos' tmlog file is a critical component for ensuring transaction durability and recovery. Key takeaways:

1. **Only recoverable states are logged** (PREPARING, IN_DOUBT, COMMITTING, ABORTING, HEUR_*)
2. **IN_DOUBT state is the most critical** - it represents the point of no return for commit decision
3. **Recovery is retry-based** - The system attempts to complete the original decision, not reverse it
4. **Heuristic outcomes are possible** - When participants cannot be reached or fail, manual intervention may be needed
5. **No automatic rollback after prepare** - Once committed to commit, the system tries to commit, not rollback

This design ensures ACID properties while exposing the fundamental limitations of distributed transactions.

---

## Scenario 3: Queue Succeeded, Database Failed, No Prepared Transaction

### Real-World Problem

A common scenario encountered in production:
- **Message appeared in the queue** (succeeded)
- **Record did NOT appear in database** (failed)
- **Oracle DBA found NO prepared transactions** in the database
- **tmlog files were not available** for inspection

**Question**: What is the most likely explanation for this scenario? Could the transaction have timed out and been rolled back?

### Answer: Timeout During Prepare Phase

**YES** - The most likely explanation is that the transaction **timed out or failed during the PREPARE phase** before ever reaching the IN_DOUBT state.

### Why This Happens

#### Timeline of Events:

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: Transaction begins
    
    note right of ACTIVE
        Application sends message to queue
        Queue operation succeeds ✓
        Transaction timeout clock starts
    end note
    
    ACTIVE --> PREPARING: commit() called
    
    note right of PREPARING
        Coordinator calls prepare() on:
        1. Queue Manager → SUCCESS ✓
        2. Database → TIMEOUT or FAILURE ✗
    end note
    
    state prepare_result <<choice>>
    PREPARING --> prepare_result: Check prepare results
    
    prepare_result --> ABORTING: Any prepare fails/timeouts
    prepare_result --> IN_DOUBT: All prepare succeed
    
    note right of ABORTING
        ✓ LOGGED to tmlog
        Automatic rollback initiated
        Rollback sent to:
        - Queue Manager (compensate)
        - Database (was never prepared)
    end note
    
    ABORTING --> TERMINATED: Rollback complete
    
    note right of TERMINATED
        Transaction rolled back cleanly
        No IN_DOUBT state reached
        Database never had prepared transaction
        Queue message remains (issue!)
    end note
    
    TERMINATED --> [*]: Transaction complete
    
    note right of IN_DOUBT
        NOT REACHED in this scenario
        This is why Oracle shows
        no prepared transactions
    end note
```

### Detailed Explanation

#### 1. Transaction Starts (ACTIVE State)
- Application code begins transaction
- Message is sent to queue and succeeds
- Database operation has not yet been prepared
- Transaction timeout clock is running

#### 2. Prepare Phase Begins (PREPARING State)
When `commit()` is called, coordinator attempts to prepare both participants:

**Queue Manager prepare()**:
- Called first (or in parallel with database)
- Returns vote: **YES** ✓
- Queue manager locks the message for commit

**Database prepare()**:
- Called but encounters one of these issues:
  - **Network timeout** to Oracle database
  - **Database response timeout** (slow query, lock contention)
  - **Transaction timeout exceeded** (> 10 seconds default)
  - **Database connection failure**
- Returns vote: **NO** ✗ or **TIMEOUT** ✗

#### 3. Prepare Failure Triggers Rollback
From `ActiveStateHandler.java` (lines 184-207):
```java
// If ANY participant votes NO or times out during prepare:
rollbackWithAfterCompletionNotification(new RollbackCallback() {
    rollbackFromWithinCallback(true, false);
});
throw new RollbackException("Prepare: NO vote");
```

**Key behavior**:
- Transaction **immediately transitions to ABORTING state**
- Coordinator sends **rollback** to Queue Manager (to undo the prepared message)
- Database is NOT sent anything (it never reached prepared state)
- Transaction moves to TERMINATED then ABANDONED
- **IN_DOUBT state is NEVER reached**

#### 4. Why Oracle Shows No Prepared Transactions
The database never entered prepared state because:
- The `prepare()` call to Oracle timed out or failed
- Oracle never created an in-doubt transaction
- The XA transaction was rolled back automatically by Atomikos
- The database connection was closed, cleaning up any partial work

#### 5. Why Queue Message Persists
This is the **critical issue** with this scenario:

**Problem**: The queue manager prepared successfully and was ready to commit, but after the database prepare failed, Atomikos sent a rollback to the queue manager. However:

- **Best case**: Queue manager successfully processes the rollback and removes the message
- **Worst case**: Queue manager already committed the message (timing issue) or the rollback fails
- **Common case**: The rollback instruction arrives, but the message was already consumed by a competing consumer

**Result**: Message appears in queue, but corresponding database record does not exist.

### Timeout Configuration

From `transactions-defaults.properties` and `ActiveStateHandler.java`:

| Setting | Default Value | Description |
|---------|--------------|-------------|
| `default_jta_timeout` | 10,000 ms (10s) | Default transaction timeout |
| `max_timeout` | 300,000 ms (5min) | Maximum allowed timeout |
| `rollback_ticks` | 30 ticks × 150ms | Time before forced rollback in ACTIVE state |

**How timeout is checked**:
- `ActiveStateHandler.onTimeout()` (lines 60-98): Called periodically (every ~150ms)
- Increments `rollbackTicks_` counter
- After 30 ticks (~4.5 seconds), forces rollback if transaction is still in ACTIVE state
- During PREPARING state, if prepare takes too long, throws timeout exception

### Most Likely Scenarios (Ordered by Probability)

#### Scenario A: Database Prepare Timeout (Most Likely)
```
1. Transaction starts, timeout = 10s
2. Queue operation completes quickly (100ms)
3. commit() called at T=8s (2 seconds before timeout)
4. Queue Manager prepare() succeeds (200ms)
5. Database prepare() is slow:
   - Network latency: 500ms
   - Locked table: waits 2+ seconds
   - Total time: > 2s remaining timeout
6. Timeout exceeded during database prepare()
7. Atomikos aborts transaction
8. Queue rollback sent (may or may not succeed)
9. Result: Queue message persists, no database record
```

**Evidence**: No tmlog entry in IN_DOUBT state, Oracle shows no prepared transaction

#### Scenario B: Database Connection Failure During Prepare
```
1. Queue operation succeeds
2. commit() called
3. Queue Manager prepare() succeeds
4. Database prepare() fails:
   - Connection lost
   - Database down
   - Network partition
5. Prepare returns NO vote
6. Atomikos rolls back transaction
7. Queue rollback sent (may fail if queue connection also lost)
```

**Evidence**: No prepared state reached, no tmlog entry

#### Scenario C: Database Prepare Exception
```
1. Queue operation succeeds
2. commit() called
3. Queue Manager prepare() succeeds
4. Database prepare() throws exception:
   - Constraint violation detected during prepare
   - Lock timeout
   - Deadlock detected
5. Atomikos rolls back transaction
6. Queue rollback sent
```

**Evidence**: Database logs would show the exception

### Diagnostics and Prevention

#### To Diagnose the Root Cause:

1. **Check Atomikos logs** around the time of incident:
   - Look for: `"Transaction X has timed out - rolling back"`
   - Look for: `"Prepare: NO vote"`
   - Look for: `"RollbackException"`

2. **Check Oracle alert logs**:
   - Look for connection errors
   - Look for ORA-02049 (timeout in distributed transaction)
   - Look for network errors

3. **Check Queue Manager logs**:
   - Look for prepare success followed by rollback
   - Check if message was committed despite rollback instruction

4. **Check application logs**:
   - Transaction timeout exceptions
   - Connection pool exhaustion
   - Slow query warnings

#### To Prevent This Issue:

1. **Increase transaction timeout**:
   ```java
   @Transactional(timeout = 30)  // 30 seconds instead of 10
   ```

2. **Optimize database operations**:
   - Add indexes to avoid table scans during prepare
   - Reduce lock contention
   - Use connection pooling properly

3. **Monitor transaction duration**:
   - Alert if transactions approach timeout threshold
   - Log prepare phase duration for each participant

4. **Implement idempotent consumers**:
   - Queue consumers should check if record exists in database
   - Prevent duplicate processing of messages

5. **Use queue transaction features**:
   - If using JMS, ensure proper XA configuration
   - Consider using transactional queues with automatic rollback

### Summary for Your Specific Scenario

**What happened**:
- Transaction started with default 10-second timeout
- Queue message send succeeded quickly
- `commit()` was called
- Queue Manager prepared successfully
- Database prepare either **timed out** or **failed** (connection issue, lock contention, slow query)
- Atomikos detected prepare failure
- Transaction rolled back **before reaching IN_DOUBT state**
- Rollback sent to Queue Manager (but message may have already been committed or consumed)
- Result: Message in queue, no database record, no prepared transaction in Oracle

**Why no tmlog entry for prepared state**:
- The transaction never reached IN_DOUBT state
- IN_DOUBT only occurs when **all participants vote YES** during prepare
- Since database prepare failed, the transaction went ACTIVE → PREPARING → ABORTING → TERMINATED
- Only ABORTING state would be logged to tmlog (if at all)

**Why Oracle shows no prepared transaction**:
- Oracle never successfully completed the prepare phase
- The XA transaction ID was never registered as "in-doubt" in Oracle
- Oracle cleaned up the partial transaction when connection closed or timeout occurred

**Could Atomikos send rollback to database after prepare succeeded?**:
- In this scenario: **NO** - the database prepare never succeeded
- In general: **NO** - as documented in Scenario 2, once all prepares succeed (IN_DOUBT state), Atomikos will not rollback

**Conclusion**: This is a **known limitation of distributed transactions** where one participant (queue) succeeds but another (database) fails during prepare. The solution requires **idempotent message processing** to handle duplicate or orphaned messages.


---

## Implementation References

Key source files analyzed:

- `TxState.java` - State definitions and transition rules
- `StateRecoveryManagerImp.java` - Triggers logging on state entry (lines 34-51)
- `OltpLogImp.java` - Validates and persists records (line 48)
- `RecoveryLogImp.java` - Recovery and cleanup operations (lines 75-102)
- `RecoveryDomainService.java` - Periodic recovery scans
- `CommitMessage.java` - Commit phase implementation (lines 54-67)
- `TerminationResult.java` - Heuristic outcome detection (lines 111-117)
- `HeurHazardStateHandler.java` - Handles HEUR_HAZARD state
- `HeurMixedStateHandler.java` - Handles HEUR_MIXED state
- `IndoubtStateHandler.java` - Handles IN_DOUBT state and timeout
- `ActiveStateHandler.java` - Handles ACTIVE state timeout and prepare phase (lines 60-98, 139-207)
- `CoordinatorImp.java` - Coordinator implementation with rollback tick configuration (lines 62-63)
- `transactions-defaults.properties` - Default timeout configuration

---

## Conclusion

Atomikos' tmlog file is a critical component for ensuring transaction durability and recovery. Key takeaways:

1. **Only recoverable states are logged** (PREPARING, IN_DOUBT, COMMITTING, ABORTING, HEUR_*)
2. **IN_DOUBT state is the most critical** - it represents the point of no return for commit decision
3. **Transactions can timeout and rollback BEFORE reaching IN_DOUBT** - This is a common scenario when prepare phase times out
4. **Recovery is retry-based** - The system attempts to complete the original decision, not reverse it
5. **Heuristic outcomes are possible** - When participants cannot be reached or fail, manual intervention may be needed
6. **No automatic rollback after prepare** - Once committed to commit, the system tries to commit, not rollback
7. **Prepare phase failures result in automatic rollback** - No IN_DOUBT state is reached, explaining scenarios where queue succeeds but database shows no prepared transaction

This design ensures ACID properties while exposing the fundamental limitations of distributed transactions.

