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
package org.moqui.impl.service.runner

import groovy.transform.CompileStatic
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceRunner
import org.moqui.util.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class RemoteRestServiceRunner implements ServiceRunner {
    protected final static Logger logger = LoggerFactory.getLogger(RemoteRestServiceRunner.class)

    RemoteRestServiceRunner() {}

    ServiceRunner init() { return this }

    Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {

        String location = sd.location
        if (!location) throw new IllegalArgumentException("Location required to call remote service ${sd.serviceName}")
        String method = sd.method
        if (method == null || method.isEmpty()) {
            // default to verb IFF it is a valid method, otherwise default to POST
            if (RestClient.METHOD_SET.contains(sd.verb.toUpperCase())) method = sd.verb
            else method = "POST"
        }

        if (location.contains('${')) {
            // TODO: consider somehow removing parameters used in location from the parameters Map,
            //     thinking of something like a ContextStack feature to watch for field names (keys) used,
            //     and then remove those from parameters Map
        }
        // logger.warn("remote-rest service call to ${rc.getUriString()}")

        // TODO/FUTURE: other options for remote authentication with headers/etc? a big limitation here, needs to be in parameters for now

        return null
    }

    void destroy() { }
}
