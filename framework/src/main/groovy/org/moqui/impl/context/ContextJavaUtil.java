/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import groovy.lang.GString;
import org.jetbrains.annotations.NotNull;
import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.util.ContextStack;
import org.moqui.util.LiteStringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Synchronization;
import javax.transaction.Transaction;
import javax.transaction.xa.XAResource;
import java.io.IOException;
import java.sql.*;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ContextJavaUtil {
    protected final static Logger logger = LoggerFactory.getLogger(ContextJavaUtil.class);
    private static final long checkSlowThreshold = 50;
    protected static final double userImpactMinMillis = 1000;

    /** the Groovy JsonBuilder doesn't handle various Moqui objects very well, ends up trying to access all
     * properties and results in infinite recursion, so need to unwrap and exclude some */
    public static Map<String, Object> unwrapMap(Map<String, Object> sourceMap) {
        Map<String, Object> targetMap = new HashMap<>();
        for (Map.Entry<String, Object> entry: sourceMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;
            // logger.warn("======== actionsResult - ${entry.key} (${entry.value?.getClass()?.getName()}): ${entry.value}")
            Object unwrapped = unwrap(key, value);
            if (unwrapped != null) targetMap.put(key, unwrapped);
        }
        return targetMap;
    }

    @SuppressWarnings("unchecked")
    public static Object unwrap(String key, Object value) {
        if (value == null) return null;
        if (value instanceof CharSequence || value instanceof Number || value instanceof java.util.Date) {
            return value;
        } else if (value instanceof EntityFind || value instanceof ExecutionContextImpl ||
                value instanceof ContextStack) {
            // intentionally skip, commonly left in context by entity-find XML action
            return null;
        } else if (value instanceof EntityValue) {
            EntityValue ev = (EntityValue) value;
            return ev.getPlainValueMap(0);
        } else if (value instanceof EntityList) {
            EntityList el = (EntityList) value;
            ArrayList<Map> newList = new ArrayList<>();
            int elSize = el.size();
            for (int i = 0; i < elSize; i++) {
                EntityValue ev = el.get(i);
                newList.add(ev.getPlainValueMap(0));
            }
            return newList;
        } else if (value instanceof Collection) {
            Collection valCol = (Collection) value;
            ArrayList<Object> newList = new ArrayList<>(valCol.size());
            for (Object entry: valCol) newList.add(unwrap(key, entry));
            return newList;
        } else if (value instanceof Map) {
            Map<Object, Object> valMap = (Map) value;
            Map<Object, Object> newMap = new HashMap<>(valMap.size());
            for (Map.Entry entry: valMap.entrySet()) newMap.put(entry.getKey(), unwrap(key, entry.getValue()));
            return newMap;
        } else {
            logger.info("In screen actions skipping value from actions block that is not supported; key=" + key + ", type=" + value.getClass().getName() + ", value=" + value);
            return null;
        }
    }

    static class RollbackInfo {
        public String causeMessage;
        /** A rollback is often done because of another error, this represents that error. */
        public Throwable causeThrowable;
        /** This is for a stack trace for where the rollback was actually called to help track it down more easily. */
        public Exception rollbackLocation;

        public RollbackInfo(String causeMessage, Throwable causeThrowable, Exception rollbackLocation) {
            this.causeMessage = causeMessage;
            this.causeThrowable = causeThrowable;
            this.rollbackLocation = rollbackLocation;
        }
    }

    static final AtomicLong moquiTxIdLast = new AtomicLong(0L);
    static class TxStackInfo {
        private TransactionFacadeImpl transactionFacade;
        public final long moquiTxId = moquiTxIdLast.incrementAndGet();
        public Exception transactionBegin = null;
        public Long transactionBeginStartTime = null;
        public int transactionTimeout = 60;
        public RollbackInfo rollbackOnlyInfo = null;

        public Transaction suspendedTx = null;
        public Exception suspendedTxLocation = null;

        Map<String, XAResource> activeXaResourceMap = new HashMap<>();
        Map<String, Synchronization> activeSynchronizationMap = new HashMap<>();
        Map<String, ConnectionWrapper> txConByGroup = new HashMap<>();
        public TransactionCache txCache = null;
        ArrayList<EntityRecordLock> recordLockList = new ArrayList<>();

        public Map<String, XAResource> getActiveXaResourceMap() { return activeXaResourceMap; }
        public Map<String, Synchronization> getActiveSynchronizationMap() { return activeSynchronizationMap; }
        public Map<String, ConnectionWrapper> getTxConByGroup() { return txConByGroup; }

        public TxStackInfo(TransactionFacadeImpl tfi) { transactionFacade = tfi; }

        public void clearCurrent() {
            rollbackOnlyInfo = null;
            transactionBegin = null;
            transactionBeginStartTime = null;
            transactionTimeout = 60;
            activeXaResourceMap.clear();
            activeSynchronizationMap.clear();
            txCache = null;
            // this should already be done, but make sure
            closeTxConnections();

            // lock track: remove all EntityRecordLock in recordLockList from TransactionFacadeImpl.recordLockByEntityPk
            int recordLockListSize = recordLockList.size();
            // if (recordLockListSize > 0) logger.warn("TOREMOVE TxStackInfo EntityRecordLock clearing " + recordLockListSize + " locks");
            for (int i = 0; i < recordLockListSize; i++) {
                EntityRecordLock erl = recordLockList.get(i);
                erl.clear(transactionFacade.recordLockByEntityPk);
            }
            recordLockList.clear();
        }

        public void closeTxConnections() {
            for (ConnectionWrapper con: txConByGroup.values()) {
                try {
                    if (con != null && !con.isClosed()) con.closeInternal();
                } catch (Throwable t) {
                    logger.error("Error closing connection for group " + con.getGroupName(), t);
                }
            }
            txConByGroup.clear();
        }
    }
    public static class EntityRecordLock {
        // TODO enum for operation? create, update, delete, find-for-update
        String entityName, pkString, entityPlusPk, threadName;
        String mutateEntityName, mutatePkString;
        long lockTime = -1, txBeginTime = -1, moquiTxId = -1;
        public EntityRecordLock(String entityName, String pkString) {
            this.entityName = entityName;
            this.pkString = pkString;
            // NOTE: used primary as a key, for efficiency don't use separator between entityName and pkString
            entityPlusPk = entityName.concat(pkString);
            threadName = Thread.currentThread().getName();
            lockTime = System.currentTimeMillis();
        }

        EntityRecordLock mutator(String mutateEntityName, String mutatePkString) {
            this.mutateEntityName = mutateEntityName;
            this.mutatePkString = mutatePkString;
            return this;
        }

        void register(ConcurrentHashMap<String, ArrayList<EntityRecordLock>> recordLockByEntityPk, TxStackInfo txStackInfo) {
            if (txStackInfo != null) {
                moquiTxId = txStackInfo.moquiTxId;
                txBeginTime = txStackInfo.transactionBeginStartTime != null ? txStackInfo.transactionBeginStartTime : -1;
            }

            ArrayList<EntityRecordLock> curErlList = recordLockByEntityPk.computeIfAbsent(entityPlusPk, k -> new ArrayList<>());
            synchronized (curErlList) {
                // is this another lock in the same transaction?
                if (curErlList.size() > 0) {
                    for (int i = 0; i < curErlList.size(); i++) {
                        EntityRecordLock otherErl = curErlList.get(i);
                        if (otherErl.moquiTxId == moquiTxId) {
                            // found a match, just return and do nothing
                            return;
                        }
                    }
                }

                // check for existing locks in this.recordLockByEntityPk, log warning if others found
                if (curErlList.size() > 0) {
                    StringBuilder msgBuilder = new StringBuilder().append("Potential lock conflict entity ").append(entityName)
                            .append(" pk ").append(pkString).append(" thread ").append(threadName)
                            .append(" TX ").append(moquiTxId).append(" began ").append(new Timestamp(txBeginTime));
                    if (mutateEntityName != null) msgBuilder.append(" from mutate of entity ").append(mutateEntityName).append(" pk ").append(mutatePkString);
                    msgBuilder.append(" at: ");
                    for (int i = 0; i < curErlList.size(); i++) {
                        EntityRecordLock otherErl = curErlList.get(i);
                        msgBuilder.append("\n== OTHER LOCK ").append(i).append(" thread ").append(otherErl.threadName)
                                .append(" TX ").append(otherErl.moquiTxId).append(" began ").append(new Timestamp(otherErl.txBeginTime)).append(" at: ");
                    }
                    logger.warn(msgBuilder.toString());
                }

                // add new lock to this.recordLockByEntityPk, and TxStackInfo.recordLockList
                if (txStackInfo != null) {
                    curErlList.add(this);
                    txStackInfo.recordLockList.add(this);
                } else {
                    logger.warn("In EntityRecordLock register no TxStackInfo so not registering lock because won't be able to clear for entity " + entityName + " pk " + pkString + " thread " + threadName);
                }
            }
        }
        void clear(ConcurrentHashMap<String, ArrayList<EntityRecordLock>> recordLockByEntityPk) {
            ArrayList<EntityRecordLock> curErlList = recordLockByEntityPk.get(entityPlusPk);
            if (curErlList == null) {
                logger.warn("In EntityRecordLock clear no locks found for " + entityPlusPk);
                return;
            }
            synchronized (curErlList) {
                boolean haveRemoved = false;
                for (int i = 0; i < curErlList.size(); i++) {
                    EntityRecordLock otherErl = curErlList.get(i);
                    if (moquiTxId == otherErl.moquiTxId) {
                        curErlList.remove(i);
                        haveRemoved = true;
                    }
                }
                if (!haveRemoved) logger.warn("In EntityRecordLock clear no locks found for " + entityPlusPk);
            }
        }
    }

    /** A simple delegating wrapper for java.sql.Connection.
     *
     * The close() method does nothing, only closed when closeInternal() called by TransactionFacade on commit,
     * rollback, or destroy (when transactions are also cleaned up as a last resort).
     *
     * Connections are attached to 2 things: entity group and transaction.
     */
    public static class ConnectionWrapper implements Connection {
        protected Connection con;
        TransactionFacadeImpl tfi;
        String groupName;

        public ConnectionWrapper(Connection con, TransactionFacadeImpl tfi, String groupName) {
            this.con = con;
            this.tfi = tfi;
            this.groupName = groupName;
        }

        public String getGroupName() { return groupName; }

        public void closeInternal() throws SQLException {
            con.close();
        }

        @Override public Statement createStatement() throws SQLException { return con.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return con.prepareStatement(sql); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return con.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return con.nativeSQL(sql); }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException { con.setAutoCommit(autoCommit); }
        @Override public boolean getAutoCommit() throws SQLException { return con.getAutoCommit(); }
        @Override public void commit() throws SQLException { con.commit(); }
        @Override public void rollback() throws SQLException { con.rollback(); }

        @Override
        public void close() throws SQLException {
            // do nothing! see closeInternal
        }

        @Override public boolean isClosed() throws SQLException { return con.isClosed(); }
        @Override public DatabaseMetaData getMetaData() throws SQLException { return con.getMetaData(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { con.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws SQLException { return con.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws SQLException { con.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return con.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { con.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return con.getTransactionIsolation(); }
        @Override public SQLWarning getWarnings() throws SQLException { return con.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { con.clearWarnings(); }

        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return con.createStatement(resultSetType, resultSetConcurrency); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return con.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return con.prepareCall(sql, resultSetType, resultSetConcurrency); }

        @Override public Map<String, Class<?>> getTypeMap() throws SQLException { return con.getTypeMap(); }
        @Override public void setTypeMap(Map<String, Class<?>> map) throws SQLException { con.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { con.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return con.getHoldability(); }
        @Override public Savepoint setSavepoint() throws SQLException { return con.setSavepoint(); }
        @Override public Savepoint setSavepoint(String name) throws SQLException { return con.setSavepoint(name); }
        @Override public void rollback(Savepoint savepoint) throws SQLException { con.rollback(savepoint); }
        @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { con.releaseSavepoint(savepoint); }

        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return con.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return con.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return con.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return con.prepareStatement(sql, autoGeneratedKeys); }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return con.prepareStatement(sql, columnIndexes); }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return con.prepareStatement(sql, columnNames); }

        @Override public Clob createClob() throws SQLException { return con.createClob(); }
        @Override public Blob createBlob() throws SQLException { return con.createBlob(); }
        @Override public NClob createNClob() throws SQLException { return con.createNClob(); }
        @Override public SQLXML createSQLXML() throws SQLException { return con.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return con.isValid(timeout); }
        @Override public void setClientInfo(String name, String value) throws SQLClientInfoException { con.setClientInfo(name, value); }
        @Override public void setClientInfo(Properties properties) throws SQLClientInfoException { con.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws SQLException { return con.getClientInfo(name); }
        @Override public Properties getClientInfo() throws SQLException { return con.getClientInfo(); }
        @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return con.createArrayOf(typeName, elements); }
        @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return con.createStruct(typeName, attributes); }

        @Override public void setSchema(String schema) throws SQLException { con.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return con.getSchema(); }

        @Override public void abort(Executor executor) throws SQLException { con.abort(executor); }
        @Override public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
            con.setNetworkTimeout(executor, milliseconds); }
        @Override public int getNetworkTimeout() throws SQLException { return con.getNetworkTimeout(); }

        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return con.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return con.isWrapperFor(iface); }

        // Object overrides
        @Override public int hashCode() { return con.hashCode(); }
        @Override public boolean equals(Object obj) { return obj instanceof Connection && con.equals(obj); }
        @Override public String toString() {
            return "Group: " + groupName + ", Con: " + con.toString();
        }
        /* these don't work, don't think we need them anyway:
        protected Object clone() throws CloneNotSupportedException {
            return new ConnectionWrapper((Connection) con.clone(), tfi, groupName) }
        protected void finalize() throws Throwable { con.finalize() }
        */
    }


    public final static ObjectMapper jacksonMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    static {
        // Jackson custom serializers, etc
        SimpleModule module = new SimpleModule();
        module.addSerializer(GString.class, new ContextJavaUtil.GStringJsonSerializer());
        module.addSerializer(LiteStringMap.class, new ContextJavaUtil.LiteStringMapJsonSerializer());
        jacksonMapper.registerModule(module);
    }
    static class GStringJsonSerializer extends StdSerializer<GString> {
        GStringJsonSerializer() { super(GString.class); }
        @Override public void serialize(GString value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException, JsonProcessingException { if (value != null) gen.writeString(value.toString()); }
    }
    static class TimestampNoNegativeJsonSerializer extends StdSerializer<Timestamp> {
        TimestampNoNegativeJsonSerializer() { super(Timestamp.class); }
        @Override public void serialize(Timestamp value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException, JsonProcessingException {
            if (value != null) {
                long time = value.getTime();
                if (time < 0) {
                    String isoUtc = value.toInstant().atZone(ZoneOffset.UTC.normalized()).format(DateTimeFormatter.ISO_INSTANT);
                    gen.writeString(isoUtc);
                    // logger.warn("Negative Timestamp " + time + ": " + isoUtc);
                } else {
                    gen.writeNumber(time);
                }
            }
        }
    }
    static class LiteStringMapJsonSerializer extends StdSerializer<LiteStringMap> {
        LiteStringMapJsonSerializer() { super(LiteStringMap.class); }
        @Override public void serialize(LiteStringMap lsm, JsonGenerator gen, SerializerProvider serializers)
                throws IOException, JsonProcessingException {
            gen.writeStartObject();
            if (lsm != null) {
                int size = lsm.size();
                for (int i = 0; i < size; i++) {
                    String key = lsm.getKey(i);
                    Object value = lsm.getValue(i);
                    // sparse maps could have null keys at certain indexes
                    if (key == null) continue;
                    gen.writeObjectField(key, value);
                }
            }
            gen.writeEndObject();
        }
    }

    // NOTE: using unbound LinkedBlockingQueue, so max pool size in ThreadPoolExecutor has no effect
    public static class WorkerThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("MoquiWorkers");
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        public Thread newThread(Runnable r) { return new Thread(workerGroup, r, "MoquiWorker-" + threadNumber.getAndIncrement()); }
    }
    public static class JobThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("MoquiJobs");
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        public Thread newThread(Runnable r) { return new Thread(workerGroup, r, "MoquiJob-" + threadNumber.getAndIncrement()); }
    }
    public static class WorkerThreadPoolExecutor extends ThreadPoolExecutor {
        private ExecutionContextFactoryImpl ecfi;
        public WorkerThreadPoolExecutor(ExecutionContextFactoryImpl ecfi, int coreSize, int maxSize, long aliveTime,
                                        TimeUnit timeUnit, BlockingQueue<Runnable> blockingQueue, ThreadFactory threadFactory) {
            super(coreSize, maxSize, aliveTime, timeUnit, blockingQueue, threadFactory);
            this.ecfi = ecfi;
        }

        @Override protected void afterExecute(Runnable runnable, Throwable throwable) {
            ExecutionContextImpl activeEc = ecfi.activeContext.get();
            if (activeEc != null) {
                logger.warn("In WorkerThreadPoolExecutor.afterExecute() there is still an ExecutionContext for runnable " + runnable.getClass().getName() + " in thread (" + Thread.currentThread().getId() + ":" + Thread.currentThread().getName() + "), destroying");
                try {
                    activeEc.destroy();
                } catch (Throwable t) {
                    logger.error("Error destroying ExecutionContext in WorkerThreadPoolExecutor.afterExecute()", t);
                }
            } else {
                if (ecfi.transactionFacade.isTransactionInPlace()) {
                    logger.error("In WorkerThreadPoolExecutor a transaction is in place for thread " + Thread.currentThread().getName() + ", trying to commit");
                    try {
                        ecfi.transactionFacade.destroyAllInThread();
                    } catch (Exception e) {
                        logger.error("WorkerThreadPoolExecutor commit in place transaction failed in thread " + Thread.currentThread().getName(), e);
                    }
                }
            }

            super.afterExecute(runnable, throwable);
        }
    }

    static class ScheduledThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("MoquiScheduled");
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        public Thread newThread(Runnable r) { return new Thread(workerGroup, r, "MoquiScheduled-" + threadNumber.getAndIncrement()); }
    }
    static class CustomScheduledTask<V> implements RunnableScheduledFuture<V> {
        public final Runnable runnable;
        public final Callable<V> callable;
        public final RunnableScheduledFuture<V> future;

        CustomScheduledTask(Runnable runnable, RunnableScheduledFuture<V> future) {
            this.runnable = runnable;
            this.callable = null;
            this.future = future;
        }
        CustomScheduledTask(Callable<V> callable, RunnableScheduledFuture<V> future) {
            this.runnable = null;
            this.callable = callable;
            this.future = future;
        }

        @Override public boolean isPeriodic() { return future.isPeriodic(); }
        @Override public long getDelay(@NotNull TimeUnit timeUnit) { return future.getDelay(timeUnit); }
        @Override public int compareTo(@NotNull Delayed delayed) { return future.compareTo(delayed); }

        @Override public void run() {
            try {
                // logger.info("Running scheduled task " + toString());
                future.run();
            } catch (Throwable t) {
                logger.error("CustomScheduledTask uncaught Throwable in run(), catching and suppressing so task does not get unscheduled", t);
            }
        }
        @Override public boolean cancel(boolean b) { return future.cancel(b); }
        @Override public boolean isCancelled() { return future.isCancelled(); }
        @Override public boolean isDone() { return future.isDone(); }

        @Override public V get() throws InterruptedException, ExecutionException { return future.get(); }
        @Override public V get(long l, @NotNull TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
            return get(l, timeUnit); }

        @Override public String toString() {
            return "CustomScheduledTask " + (runnable != null ? runnable.getClass().getName() : (callable != null ? callable.getClass().getName() : "[no Runnable or Callable!]"));
        }
    }
    static class CustomScheduledExecutor extends ScheduledThreadPoolExecutor {
        public CustomScheduledExecutor(int coreThreads) {
            super(coreThreads, new ScheduledThreadFactory());
        }
        protected <V> RunnableScheduledFuture<V> decorateTask(Runnable r, RunnableScheduledFuture<V> task) {
            return new CustomScheduledTask<V>(r, task);
        }
        protected <V> RunnableScheduledFuture<V> decorateTask(Callable<V> c, RunnableScheduledFuture<V> task) {
            return new CustomScheduledTask<V>(c, task);
        }
    }
    static class ScheduledRunnableInfo {
        public final Runnable command;
        public final long period;
        // NOTE: tracking initial ScheduledFuture is useless as it gets replaced with each run: public final ScheduledFuture scheduledFuture;
        ScheduledRunnableInfo(Runnable command, long period) {
            this.command = command; this.period = period;
        }
    }
}
