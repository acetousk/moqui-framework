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
package org.moqui.impl.service;

import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.service.ServiceCall;
import org.moqui.service.ServiceException;

import java.util.HashMap;
import java.util.Map;

public class ServiceCallImpl implements ServiceCall {
    protected String path = null;
    protected String verb = null;
    protected String noun = null;
    protected ServiceDefinition sd = null;
    protected boolean noSd = false;
    protected String serviceName = null;
    protected String serviceNameNoHash = null;
    protected Map<String, Object> parameters = new HashMap<>();

    protected void serviceNameInternal(String serviceName) {
        if (serviceName == null || serviceName.isEmpty()) throw new ServiceException("Service name cannot be empty");
        path = ServiceDefinition.getPathFromName(serviceName);
        verb = ServiceDefinition.getVerbFromName(serviceName);
        noun = ServiceDefinition.getNounFromName(serviceName);
        // if the service is not found must be an entity auto, but if there is a path then error
        if (path == null || path.isEmpty()) {
            noSd = true;
        } else {
            throw new ServiceException("Service not found with name " + serviceName);
        }
        this.serviceName = serviceName;
        serviceNameNoHash = serviceName.replace("#", "");
    }

    public ServiceCallImpl() {  }

    protected void serviceNameInternal(String path, String verb, String noun) {
        if (path == null || path.isEmpty()) {
            noSd = true;
        } else {
            this.path = path;
        }
        this.verb = verb;
        this.noun = noun;
        StringBuilder sb = new StringBuilder();
        if (!noSd) sb.append(path).append('.');
        sb.append(verb);
        if (noun != null && !noun.isEmpty()) sb.append('#').append(noun);
        serviceName = sb.toString();
        if (noSd) {
            serviceNameNoHash = serviceName.replace("#", "");
        } else {
            sd = null;
            if (sd == null) throw new ServiceException("Service not found with name " + serviceName + " (path: " + path + ", verb: " + verb + ", noun: " + noun + ")");
            serviceNameNoHash = sd.serviceNameNoHash;
        }
    }

    @Override
    public String getServiceName() { return serviceName; }

    @Override
    public Map<String, Object> getCurrentParameters() {
        return parameters;
    }

    public ServiceDefinition getServiceDefinition() {
        // this should now never happen, sd now always set on name set
        // if (sd == null && !noSd) sd = sfi.getServiceDefinition(serviceName);
        return sd;
    }

    public boolean isEntityAutoPattern() {
        return noSd;
        // return sfi.isEntityAutoPattern(path, verb, noun);
    }

    public void validateCall(ExecutionContextImpl eci) {
        // Before scheduling the service check a few basic things so they show up sooner than later:
        ServiceDefinition sd = null;
        if (sd == null && !isEntityAutoPattern())
            throw new ServiceException("Could not find service with name [" + getServiceName() + "]");

        // always do an authz before scheduling the job
        // pop immediately, just did the push to to an authz

        // logger.warn("=========== async call ${serviceName}, parameters: ${parameters}")
    }
}
