# syntax=docker/dockerfile:1

# Base build stage
FROM gradle:7.4.1-jdk11 AS build
WORKDIR /opt/moqui
LABEL org.opencontainers.image.authors="moqui@googlegroups.com"

# Arguments
# create user for search and chown corresponding files
ARG GRADLE_COMMAND=""
ARG GRADLE_ARGS="--info --no-daemon --parallel"
ARG COMPONENT=""
ARG COMPONENT_SET="acetousk"
ARG RUN_LOCAL_SEARCH="true"
ARG search_name="opensearch"

# Copy source code
COPY . ./

# Build and unzip application
RUN sh start.sh && unzip -q -o moqui-plus-runtime.war

# Copied from docker/simple/Dockerfile
# Builds a minimal docker image with openjdk and moqui with various volumes for configuration and persisted data outside the container
# NOTE: add components, build and if needed load data before building a docker image with this
FROM eclipse-temurin:11-jre
WORKDIR /opt/moqui
ARG search_name=opensearch

COPY --from=build /opt/moqui/WEB-INF WEB-INF
COPY --from=build /opt/moqui/META-INF META-INF
COPY --from=build /opt/moqui/*.class ./
COPY --from=build /opt/moqui/execlib execlib
COPY --from=build /opt/moqui/runtime runtime

RUN if [ "$RUN_LOCAL_SEARCH" == "true" ]; then \
      if [ -d runtime/opensearch/bin ]; then \
        echo "Installing OpenSearch User"; \
        search_name=opensearch; \
        groupadd -g 1000 opensearch 2>/dev/null || echo "group 1000 already exists" && \
        useradd -u 1000 -g 1000 -G 0 -d /opt/moqui/runtime/opensearch opensearch 2>/dev/null || echo "user 1000 already exists" && \
        chmod 0775 /opt/moqui/runtime/opensearch && \
        chown -R 1000:0 /opt/moqui/runtime/opensearch; \
      fi; \
      if [ -d runtime/elasticsearch/bin ]; then \
        echo "Installing ElasticSearch User"; \
        search_name=elasticsearch; \
        groupadd -r elasticsearch && \
        useradd --no-log-init -r -g elasticsearch -d /opt/moqui/runtime/elasticsearch elasticsearch && \
        chown -R elasticsearch:elasticsearch runtime/elasticsearch; \
      fi; \
    fi

# exposed as volumes for configuration purposes
VOLUME ["/opt/moqui/runtime/conf", "/opt/moqui/runtime/lib", "/opt/moqui/runtime/classes", "/opt/moqui/runtime/ecomponent"]
# exposed as volumes to persist data outside the container, recommended
VOLUME ["/opt/moqui/runtime/log", "/opt/moqui/runtime/txlog", "/opt/moqui/runtime/sessions", "/opt/moqui/runtime/db", "/opt/moqui/runtime/$search_name"]

# Main Servlet Container Port, Search HTTP Port, Search Cluster (TCP Transport) Port, Hazelcast Cluster Port
EXPOSE 80 9200 9300 5701

# this is to run from the war file directly, preferred approach unzips war file in advance
# ENTRYPOINT ["java", "-jar", "moqui.war"]
ENTRYPOINT ["java", "-cp", ".", "MoquiStart", "port=80"]

HEALTHCHECK --interval=15s --timeout=600ms --start-period=15s CMD curl -f -H "X-Forwarded-Proto: https" -H "X-Forwarded-Ssl: on" http://localhost/status || exit 1
# specify this as a default parameter if none are specified with docker exec/run, ie run production by default
CMD ["conf=conf/MoquiProductionConf.xml"]
