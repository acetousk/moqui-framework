package org.moqui.impl.service;

import groovy.lang.Closure;
import org.moqui.BaseException;
import org.moqui.context.*;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.*;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.EntitySqlException;
import org.moqui.impl.service.runner.EntityAutoServiceRunner;
import org.moqui.service.ServiceCallSync;
import org.moqui.service.ServiceException;
import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Status;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceCallSyncImpl extends ServiceCallImpl implements ServiceCallSync {
    private static final Logger logger = LoggerFactory.getLogger(ServiceCallSyncImpl.class);
    private static final boolean traceEnabled = logger.isTraceEnabled();

    private boolean ignoreTransaction = false;
    private boolean requireNewTransaction = false;
    private Boolean useTransactionCache = null;
    private Integer transactionTimeout = null;
    private boolean ignorePreviousError = false;
    private boolean softValidate = false;
    private boolean multi = false;
    private boolean rememberParameters = true;
    protected boolean disableAuthz = false;

    public ServiceCallSyncImpl() {  }

    @Override public ServiceCallSync name(String serviceName) { serviceNameInternal(serviceName); return this; }
    @Override public ServiceCallSync name(String v, String n) { serviceNameInternal(null, v, n); return this; }
    @Override public ServiceCallSync name(String p, String v, String n) { serviceNameInternal(p, v, n); return this; }

    @Override public ServiceCallSync parameters(Map<String, ?> map) { if (map != null) parameters.putAll(map); return this; }
    @Override public ServiceCallSync parameter(String name, Object value) { parameters.put(name, value); return this; }

    @Override public ServiceCallSync ignoreTransaction(boolean it) { this.ignoreTransaction = it; return this; }
    @Override public ServiceCallSync requireNewTransaction(boolean rnt) { this.requireNewTransaction = rnt; return this; }
    @Override public ServiceCallSync useTransactionCache(boolean utc) { this.useTransactionCache = utc; return this; }
    @Override public ServiceCallSync transactionTimeout(int timeout) { this.transactionTimeout = timeout; return this; }

    @Override public ServiceCallSync ignorePreviousError(boolean ipe) { this.ignorePreviousError = ipe; return this; }
    @Override public ServiceCallSync softValidate(boolean sv) { this.softValidate = sv; return this; }
    @Override public ServiceCallSync multi(boolean mlt) { this.multi = mlt; return this; }
    @Override public ServiceCallSync disableAuthz() { disableAuthz = true; return this; }
    @Override public ServiceCallSync noRememberParameters() { rememberParameters = false; return this; }

    @Override
    public Map<String, Object> call() {
        ExecutionContextFactoryImpl ecfi = null;
        ExecutionContextImpl eci = ecfi.getEci();

        try {
            if (multi) {
                ArrayList<String> inParameterNames = null;
                if (sd != null) {
                    inParameterNames = sd.getInParameterNames();
                } else if (isEntityAutoPattern()) {
                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(noun);
                    if (ed != null) inParameterNames = ed.getAllFieldNames();
                }

                int inParameterNamesSize = inParameterNames != null ? inParameterNames.size() : 0;
                // run all service calls in a single transaction for multi form submits, ie all succeed or fail together
                boolean beganTransaction = false;
                try {
                    Map<String, Object> result = new HashMap<>();
                    for (int i = 0; ; i++) {
                        Map<String, Object> currentParms = new HashMap<>();
                        for (int paramIndex = 0; paramIndex < inParameterNamesSize; paramIndex++) {
                            String ipn = inParameterNames.get(paramIndex);
                            String key = ipn + "_" + i;
                            if (parameters.containsKey(key)) currentParms.put(ipn, parameters.get(key));
                        }

                        // if the map stayed empty we have no parms, so we're done
                        if (currentParms.size() == 0) break;

                        if (("true".equals(parameters.get("_useRowSubmit")) || "true".equals(parameters.get("_useRowSubmit_" + i)))
                                && !"true".equals(parameters.get("_rowSubmit_" + i))) continue;

                        // now that we have checked the per-row parameters, add in others available
                        for (int paramIndex = 0; paramIndex < inParameterNamesSize; paramIndex++) {
                            String ipn = inParameterNames.get(paramIndex);
                            if (!ObjectUtilities.isEmpty(currentParms.get(ipn))) continue;
                            if (!ObjectUtilities.isEmpty(parameters.get(ipn))) {
                                currentParms.put(ipn, parameters.get(ipn));
                            } else if (!ObjectUtilities.isEmpty(result.get(ipn))) {
                                currentParms.put(ipn, result.get(ipn));
                            }
                        }

                        // call the service
                        Map<String, Object> singleResult = callSingle(currentParms, sd, eci);
                        if (singleResult != null) result.putAll(singleResult);
                        // ... and break if there are any errors
                    }

                    return result;
                } catch (Throwable t) {
                    throw t;
                } finally {
                }
            } else {
                return callSingle(parameters, sd, eci);
            }
        } finally {
        }
    }

    private Map<String, Object> callSingle(Map<String, Object> currentParameters, ServiceDefinition sd, final ExecutionContextImpl eci) {
        // NOTE: checking this here because service won't generally run after input validation, etc anyway

        int transactionStatus = Status.STATUS_MARKED_ROLLBACK;
        if (!requireNewTransaction && transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            return null;
        }

        if (traceEnabled) logger.trace("Calling service " + serviceName + " initial input: " + currentParameters);

        // get these before cleaning up the parameters otherwise will be removed
        String username = null;
        String password = null;
        if (currentParameters.containsKey("authUsername")) {
            username = (String) currentParameters.get("authUsername");
            password = (String) currentParameters.get("authPassword");
        } else if (currentParameters.containsKey("authUserAccount")) {
            Map authUserAccount = (Map) currentParameters.get("authUserAccount");
            username = (String) authUserAccount.get("username");
            if (username == null || username.isEmpty()) username = (String) currentParameters.get("authUsername");
            password = (String) authUserAccount.get("currentPassword");
            if (password == null || password.isEmpty()) password = (String) currentParameters.get("authPassword");
        }

        final String serviceType = sd != null ? sd.serviceType : "entity-implicit";
        ArrayList<ServiceEcaRule> secaRules = null;
        boolean hasSecaRules = secaRules != null && secaRules.size() > 0;

        // in-parameter validation
        if (sd != null) {
            currentParameters = sd.convertValidateCleanParameters(currentParameters, eci);
            if (softValidate) {
            }
        }
        // if error(s) in parameters, return now with no results

        boolean userLoggedIn = false;

        // always try to login the user if parameters are specified
        if (username != null && password != null && username.length() > 0 && password.length() > 0) {
            // if user was not logged in we should already have an error message in place so just return
            if (!userLoggedIn) return null;
        }

        if (sd != null && "true".equals(sd.authenticate)) {
            throw new AuthenticationRequiredException("User must be logged in to call service " + serviceName);
        }

        if (sd == null) {
            logger.info("No service with name " + serviceName + ", isEntityAutoPattern=" + isEntityAutoPattern() +
                    ", path=" + path + ", verb=" + verb + ", noun=" + noun + ", noun is entity? " + eci.getEntityFacade().isEntityDefined(noun));
            throw new ServiceException("Could not find service with name " + serviceName);
        }

        if ("interface".equals(serviceType)) {
            throw new ServiceException("Service " + serviceName + " is an interface and cannot be run");
        }

        ServiceRunner serviceRunner = sd.serviceRunner;
        if (serviceRunner == null) {
            throw new ServiceException("Could not find service runner for type " + serviceType + " for service " + serviceName);
        }

        // pre authentication and authorization SECA rules

        // push service call artifact execution, checks authz too
        // NOTE: don't require authz if the service def doesn't authenticate
        // NOTE: if no sd then requiresAuthz is false, ie let the authz get handled at the entity level (but still put
        //     the service on the stack)

        // if error in auth or for other reasons, return now with no results

        // must be done after the artifact execution push so that AEII object to set anonymous authorized is in place
        boolean loggedInAnonymous = false;
        if (sd != null && "anonymous-all".equals(sd.authenticate)) {
        } else if (sd != null && "anonymous-view".equals(sd.authenticate)) {
        }

        // handle sd.serviceNode."@semaphore"; do this BEFORE local transaction created, etc so waiting for this doesn't cause TX timeout
        if (sd.hasSemaphore) {
            try {
                checkAddSemaphore(eci, currentParameters, true);
            } catch (Throwable t) {
                throw t;
            }
        }

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false;
        boolean beginTransactionIfNeeded = true;
        if (ignoreTransaction || sd.txIgnore) beginTransactionIfNeeded = false;
        if (requireNewTransaction || sd.txForceNew) pauseResumeIfNeeded = true;

        boolean suspendedTransaction = false;
        Map<String, Object> result = new HashMap<>();
        try {
            if (pauseResumeIfNeeded && transactionStatus != Status.STATUS_NO_TRANSACTION) {
                suspendedTransaction = false;
                transactionStatus = Status.STATUS_MARKED_ROLLBACK;
            }
            boolean beganTransaction = false;
            if (beginTransactionIfNeeded && transactionStatus != Status.STATUS_ACTIVE) {
                // logger.warn("Service " + serviceName + " begin TX timeout " + transactionTimeout + " SD txTimeout " + sd.txTimeout);
                beganTransaction = false;
                transactionStatus = Status.STATUS_MARKED_ROLLBACK;
            }
            if (sd.noTxCache) {
            } else {
                // alternative to use read only TX cache by default, not functional yet: tf.initTransactionCache(!(useTransactionCache != null ? useTransactionCache : sd.txUseCache));
            }

            try {
                if (traceEnabled) logger.trace("Calling service " + serviceName + " pre-call input: " + currentParameters);

                // if error(s) in pre-service or anything else before actual run then return now with no results

                try {
                    // run the service through the ServiceRunner
                    result = serviceRunner.runService(sd, currentParameters);
                } finally {
                }
                // logger.warn("Called " + serviceName + " has error message " + eci.messageFacade.hasError() + " began TX " + beganTransaction + " TX status " + tf.getStatusString());

                // post-service SECA rules
                // registered callbacks, no Throwable
                // if we got any errors added to the message list in the service, rollback for that too

                if (traceEnabled) logger.trace("Calling service " + serviceName + " result: " + result);
            } catch (Throwable t) {
                BaseException.filterStackTrace(t);
                // registered callbacks with Throwable
                // rollback the transaction
                transactionStatus = Status.STATUS_MARKED_ROLLBACK;
                logger.warn("Error running service " + serviceName + " (Throwable) Artifact stack: ", t);
                // add all exception messages to the error messages list
                Throwable parent = t.getCause();
                while (parent != null) {
                    parent = parent.getCause();
                }
            } finally {
                try {
                    if (beganTransaction) {
                        transactionStatus = Status.STATUS_MARKED_ROLLBACK;
                        if (transactionStatus == Status.STATUS_ACTIVE) {
                        } else if (transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
                            // will rollback based on marked rollback only
                        }
                        /* most likely in this case is no transaction in place, already rolled back above, do nothing:
                        else {
                            logger.warn("In call to service " + serviceName + " transaction not Active or Marked Rollback-Only (" + tf.getStatusString() + "), doing commit to make sure TX closed");
                            tf.commit();
                        }
                        */
                    }
                } catch (Throwable t) {
                    logger.warn("Error committing transaction for service " + serviceName, t);
                    // add all exception messages to the error messages list
                    Throwable parent = t.getCause();
                    while (parent != null) {
                        parent = parent.getCause();
                    }

                }
            }

            return result;
        } finally {
            // clear the semaphore
            if (sd.hasSemaphore) clearSemaphore(eci, currentParameters);

            try {
            } catch (Throwable t) {
                logger.error("Error resuming parent transaction after call to service " + serviceName, t);
            }

            try {
            } catch (Throwable t) {
                logger.error("Error logging out user after call to service " + serviceName, t);
            }

            // all done so pop the artifact info
            // restore error messages if needed

        }

    }

    @SuppressWarnings("unused")
    private void clearSemaphore(final ExecutionContextImpl eci, Map<String, Object> currentParameters) {
        final String semaphoreName = sd.semaphoreName != null && !sd.semaphoreName.isEmpty() ? sd.semaphoreName : serviceName;
        String semParameter = sd.semaphoreParameter;
        String parameterValue;
        if (semParameter == null || semParameter.isEmpty()) {
            parameterValue = "_NA_";
        } else {
            Object parmObj = currentParameters.get(semParameter);
            parameterValue = parmObj != null ? parmObj.toString() : "_NULL_";
        }

    }

    /* A good test case is the place#Order service which is used in the AssetReservationMultipleThreads.groovy tests:
        conflicting lock:
            <service verb="place" noun="Order" semaphore="wait" semaphore-name="TestOrder">
        segemented lock (bad in practice, good test with transacitonal ID):
            <service verb="place" noun="Order" semaphore="wait" semaphore-name="TestOrder" semaphore-parameter="orderId">
     */
    @SuppressWarnings("unused")
    private void checkAddSemaphore(final ExecutionContextImpl eci, Map<String, Object> currentParameters, boolean allowRetry) {
        final String semaphore = sd.semaphore;
        final String semaphoreName = sd.semaphoreName != null && !sd.semaphoreName.isEmpty() ? sd.semaphoreName : serviceName;
        String semaphoreParameter = sd.semaphoreParameter;
        final String parameterValue;
        if (semaphoreParameter == null || semaphoreParameter.isEmpty()) {
            parameterValue = "_NA_";
        } else {
            Object parmObj = currentParameters.get(semaphoreParameter);
            parameterValue = parmObj != null ? parmObj.toString() : "_NULL_";
        }

        final long semaphoreIgnoreMillis = sd.semaphoreIgnoreMillis;
        final long semaphoreSleepTime = sd.semaphoreSleepTime;
        final long semaphoreTimeoutTime = sd.semaphoreTimeoutTime;
        final int txTimeout = Math.toIntExact(sd.semaphoreTimeoutTime / 1000) * 2;

        // NOTE: get Thread name outside runRequireNew otherwise will always be RequireNewTx
        final String lockThreadName = Thread.currentThread().getName();
        // support a single wait/retry on error creating semaphore record
        AtomicBoolean retrySemaphore = new AtomicBoolean(false);

        if (allowRetry && retrySemaphore.get()) {
            checkAddSemaphore(eci, currentParameters, false);
        }
    }

    private Map<String, Object> runImplicitEntityAuto(Map<String, Object> currentParameters, ArrayList<ServiceEcaRule> secaRules, ExecutionContextImpl eci) {
        // NOTE: no authentication, assume not required for this; security settings can override this and require
        //     permissions, which will require authentication
        // done in calling method: sfi.runSecaRules(serviceName, currentParameters, null, "pre-auth")

        boolean hasSecaRules = secaRules != null && secaRules.size() > 0;

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false;
        boolean beginTransactionIfNeeded = true;
        if (ignoreTransaction) beginTransactionIfNeeded = false;
        if (requireNewTransaction) pauseResumeIfNeeded = true;

        boolean suspendedTransaction = false;
        Map<String, Object> result = new HashMap<>();
        try {
            if (pauseResumeIfNeeded) suspendedTransaction = false;
            boolean beganTransaction = beginTransactionIfNeeded;

            // alternative to use read only TX cache by default, not functional yet: tf.initTransactionCache(useTransactionCache == null || !useTransactionCache);

            try {
                // if error(s) in pre-service or anything else before actual run then return now with no results

                try {
                    EntityDefinition ed = eci.getEntityFacade().getEntityDefinition(noun);
                    if ("create".equals(verb)) {
                        EntityAutoServiceRunner.createEntity(eci, ed, currentParameters, result, null);
                    } else if ("update".equals(verb)) {
                        EntityAutoServiceRunner.updateEntity(eci, ed, currentParameters, result, null, null);
                    } else if ("delete".equals(verb)) {
                        EntityAutoServiceRunner.deleteEntity(eci, ed, currentParameters);
                    } else if ("store".equals(verb)) {
                        EntityAutoServiceRunner.storeEntity(eci, ed, currentParameters, result, null);
                    }

                    // NOTE: no need to throw exception for other verbs, checked in advance when looking for valid service name by entity auto pattern
                } finally {
                }
            } catch (Throwable t) {
                logger.error("Error running service " + serviceName, t);
                // add all exception messages to the error messages list
                Throwable parent = t.getCause();
                while (parent != null) {
                    parent = parent.getCause();
                }
            } finally {
                try {
                } catch (Throwable t) {
                    logger.warn("Error committing transaction for entity-auto service " + serviceName, t);
                    // add all exception messages to the error messages list
                    Throwable parent = t.getCause();
                    while (parent != null) {
                        parent = parent.getCause();
                    }
                }
            }
        } finally {
        }

        return result;
    }
}
