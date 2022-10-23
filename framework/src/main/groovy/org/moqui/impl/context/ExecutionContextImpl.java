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

import groovy.lang.Closure;
import org.moqui.context.*;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.EntityFacadeImpl;
import org.moqui.util.ContextBinding;
import org.moqui.util.ContextStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.concurrent.Future;

public class ExecutionContextImpl implements ExecutionContext {
    private static final Logger loggerDirect = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class);

    public final ExecutionContextFactoryImpl ecfi;
    public final ContextStack contextStack = new ContextStack();
    public final ContextBinding contextBindingInternal = new ContextBinding(contextStack);

    private EntityFacadeImpl activeEntityFacade;

    // local references to ECFI fields
    public final LoggerFacadeImpl loggerFacade;
    public final TransactionFacadeImpl transactionFacade;

    private Boolean skipStats = null;
    private Cache<String, String> l10nMessageCache;
    private Cache<String, ArrayList> tarpitHitCache;

    public String forThreadName;
    public long forThreadId;
    // public final Exception createLoc;

    public ExecutionContextImpl(ExecutionContextFactoryImpl ecfi, Thread forThread) {
        this.ecfi = ecfi;
        // NOTE: no WebFacade init here, wait for call in to do that
        // put reference to this in the context root
        contextStack.put("ec", this);
        forThreadName = forThread.getName();
        forThreadId = forThread.getId();
        // createLoc = new BaseException("ec create");

        activeEntityFacade = ecfi.entityFacade;

        loggerFacade = ecfi.loggerFacade;
        transactionFacade = ecfi.transactionFacade;

        if (loggerFacade == null) throw new IllegalStateException("loggerFacade was null");
        if (transactionFacade == null) throw new IllegalStateException("transactionFacade was null");

        initCaches();

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl initialized");
    }

    @SuppressWarnings("unchecked")
    private void initCaches() {
    }
    Cache<String, String> getL10nMessageCache() { return l10nMessageCache; }
    public Cache<String, ArrayList> getTarpitHitCache() { return tarpitHitCache; }

    @Override public @Nonnull ExecutionContextFactory getFactory() { return ecfi; }

    @Override public @Nonnull ContextStack getContext() { return contextStack; }
    @Override public @Nonnull Map<String, Object> getContextRoot() { return contextStack.getRootMap(); }
    @Override public @Nonnull ContextBinding getContextBinding() { return contextBindingInternal; }

    @Override
    public <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters) {
        return ecfi.getTool(toolName, instanceClass, parameters);
    }

    @Override public @Nonnull LoggerFacade getLogger() { return loggerFacade; }
    @Override public @Nonnull TransactionFacade getTransaction() { return transactionFacade; }

    @Override public @Nonnull EntityFacade getEntity() { return activeEntityFacade; }
    public @Nonnull EntityFacadeImpl getEntityFacade() { return activeEntityFacade; }

    @Override
    public void initWebFacade(@Nonnull String webappMoquiName, @Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response) {

        // now that we have the webFacade in place we can do init UserFacade
        // for convenience (and more consistent code in screen actions, services, etc) add all requestParameters to the context
        // this is the beginning of a request, so trigger before-request actions

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl WebFacade initialized");
    }

    /** Meant to be used to set a test stub that implements the WebFacade interface */

    public boolean getSkipStats() {
        if (skipStats != null) return skipStats;
        String skipStatsCond = ecfi.skipStatsCond;
        Map<String, Object> skipParms = new HashMap<>();
        skipStats = (skipStatsCond != null && !skipStatsCond.isEmpty()) && false;
        return skipStats;
    }

    @Override
    public Future runAsync(@Nonnull Closure closure) {
        ThreadPoolRunnable runnable = new ThreadPoolRunnable(this, closure);
        return ecfi.workerPool.submit(runnable);
    }
    /** Uses the ECFI constructor for ThreadPoolRunnable so does NOT use the current ECI in the separate thread */
    public Future runInWorkerThread(@Nonnull Closure closure) {
        ThreadPoolRunnable runnable = new ThreadPoolRunnable(ecfi, closure);
        return ecfi.workerPool.submit(runnable);
    }

    @Override
    public void destroy() {
        // if webFacade exists this is the end of a request, so trigger after-request actions

        // make sure there are no transactions open, if any commit them all now
        ecfi.transactionFacade.destroyAllInThread();
        // clean up resources, like JCR session
        // clear out the ECFI's reference to this as well
        ecfi.activeContext.remove();
        ecfi.activeContextMap.remove(Thread.currentThread().getId());

        MDC.remove("moqui_userId");
        MDC.remove("moqui_visitorId");

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl destroyed");
    }

    @Override public String toString() { return "ExecutionContext"; }

    public static class ThreadPoolRunnable implements Runnable {
        private ExecutionContextImpl threadEci;
        private ExecutionContextFactoryImpl ecfi;
        private Closure closure;
        /** With this constructor (passing ECI) the ECI is used in the separate thread */
        public ThreadPoolRunnable(ExecutionContextImpl eci, Closure closure) {
            threadEci = eci;
            ecfi = eci.ecfi;
            this.closure = closure;
        }

        /** With this constructor (passing ECFI) a new ECI is created for the separate thread */
        public ThreadPoolRunnable(ExecutionContextFactoryImpl ecfi, Closure closure) {
            this.ecfi = ecfi;
            threadEci = null;
            this.closure = closure;
        }

        @Override
        public void run() {
            if (threadEci != null) {
                // ecfi.useExecutionContextInThread(threadEci);
                ExecutionContextImpl eci = ecfi.getEci();
            }
            try {
                closure.call();
            } catch (Throwable t) {
                loggerDirect.error("Error in EC worker Runnable", t);
            } finally {
                // now using separate ECI in thread so always destroy, ie don't do: if (threadEci == null)
                ecfi.destroyActiveExecutionContext();
            }
        }

        public ExecutionContextFactoryImpl getEcfi() { return ecfi; }
        public void setEcfi(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi; }
        public Closure getClosure() { return closure; }
        public void setClosure(Closure closure) { this.closure = closure; }
    }
}
