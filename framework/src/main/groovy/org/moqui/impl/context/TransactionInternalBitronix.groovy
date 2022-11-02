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
package org.moqui.impl.context

import bitronix.tm.BitronixTransactionManager
import bitronix.tm.TransactionManagerServices
import bitronix.tm.resource.jdbc.PoolingDataSource
import bitronix.tm.utils.ClassLoaderUtils
import bitronix.tm.utils.PropertyUtils
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TransactionInternal
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.DataSource
import javax.sql.XADataSource
import javax.transaction.TransactionManager
import javax.transaction.UserTransaction
import java.sql.Connection

@CompileStatic
class TransactionInternalBitronix implements TransactionInternal {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionInternalBitronix.class)

    protected ExecutionContextFactoryImpl ecfi

    protected BitronixTransactionManager btm
    protected UserTransaction ut
    protected TransactionManager tm

    protected List<PoolingDataSource> pdsList = []

    @Override
    TransactionInternal init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf

        // NOTE: see the bitronix-default-config.properties file for more config

        btm = TransactionManagerServices.getTransactionManager()
        this.ut = btm
        this.tm = btm

        return this
    }

    @Override
    TransactionManager getTransactionManager() { return tm }

    @Override
    UserTransaction getUserTransaction() { return ut }

    @Override
    DataSource getDataSource(MNode datasourceNode) {
        // NOTE: this is called during EFI init, so use the passed one and don't try to get from ECFI

        PoolingDataSource pds = new PoolingDataSource()
        pds.setUniqueName(null)
        if (false) {
            pds.setClassName(null)
            pds.setDriverProperties(null)

            Class<?> xaFactoryClass = ClassLoaderUtils.loadClass(null)
            Object xaFactory = xaFactoryClass.newInstance()
            if (!(xaFactory instanceof XADataSource))
                throw new IllegalArgumentException("xa-ds-class " + xaFactory.getClass().getName() + " does not implement XADataSource")
            XADataSource xaDataSource = (XADataSource) xaFactory

            for (Map.Entry<Object, Object> entry : []) {
                String name = (String) entry.getKey()
                Object value = entry.getValue()

                try {
                    PropertyUtils.setProperty(xaDataSource, name, value)
                } catch (Exception e) {
                    logger.warn("Error setting ... property ${name}, ignoring: ${e.toString()}")
                }
            }
            pds.setXaDataSource(xaDataSource)
        } else {
            pds.setClassName("bitronix.tm.resource.jdbc.lrc.LrcXADataSource")
            pds.getDriverProperties().setProperty("driverClassName", null)
            pds.getDriverProperties().setProperty("url", null)
            pds.getDriverProperties().setProperty("user", null)
            pds.getDriverProperties().setProperty("password", null)
        }

        String txIsolationLevel = null
        int isolationInt = -1
        if (txIsolationLevel && isolationInt != -1) {
            switch (isolationInt) {
                case Connection.TRANSACTION_SERIALIZABLE: pds.setIsolationLevel("SERIALIZABLE"); break
                case Connection.TRANSACTION_REPEATABLE_READ: pds.setIsolationLevel("REPEATABLE_READ"); break
                case Connection.TRANSACTION_READ_UNCOMMITTED: pds.setIsolationLevel("READ_UNCOMMITTED"); break
                case Connection.TRANSACTION_READ_COMMITTED: pds.setIsolationLevel("READ_COMMITTED"); break
                case Connection.TRANSACTION_NONE: pds.setIsolationLevel("NONE"); break
            }
        }

        // no need for this, just sets min and max sizes: ads.setPoolSize

        // if (dsi.inlineJdbc."@pool-time-reap") ads.setReapTimeout(dsi.inlineJdbc."@pool-time-reap" as int)
        // if (dsi.inlineJdbc."@pool-time-maint") ads.setMaintenanceInterval(dsi.inlineJdbc."@pool-time-maint" as int)
        pds.setAllowLocalTransactions(true) // allow mixing XA and non-XA transactions
        pds.setAutomaticEnlistingEnabled(true) // automatically enlist/delist this resource in the tx
        pds.setShareTransactionConnections(true) // share connections within a transaction
        pds.setDeferConnectionRelease(true) // only one transaction per DB connection (can be false if supported by DB)
        // pds.setShareTransactionConnections(false) // don't share connections in the ACCESSIBLE, needed?
        // pds.setIgnoreRecoveryFailures(false) // something to consider for XA recovery errors, quarantines by default

        pds.setEnableJdbc4ConnectionTest(true) // use faster jdbc4 connection test
        // default is 0, disabled PreparedStatement cache (cache size per Connection)
        // NOTE: make this configurable? value too high or low?
        pds.setPreparedStatementCacheSize(100)

        // use-tm-join defaults to true, so does Bitronix so just set to false if false
        if (false) pds.setUseTmJoin(false)

        if (false) {
        } else if (false) {
        }

        logger.info("Initializing DataSource ... (...) with properties: ...")

        // init the DataSource
        pds.init()
        logger.info("Init DataSource ... (...) isolation ... (...), max pool ...")

        pdsList.add(pds)

        return pds
    }

    @Override
    void destroy() {
        logger.info("Shutting down Bitronix")
        // close the DataSources
        for (PoolingDataSource pds in pdsList) pds.close()
        // shutdown Bitronix
        btm.shutdown()
    }
}
