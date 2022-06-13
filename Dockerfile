# syntax=docker/dockerfile:1

FROM gradle:7.4.2-jdk11 AS start
WORKDIR /opt/moqui
ARG GRADLE_COMMAND=""
ARG GRADLE_ARGS="--info --no-daemon --parallel"
ARG USE_HAZELCAST="false"
ENV webapp_http_port=80
ENV open_search_port=9200
ENV open_search_cluster_port=9600
ENV hazelcast_port=5701
ENV h2_console_port=9091
COPY . ./
RUN ["sh", "start.sh"]
RUN ["unzip", "-q", "-o", "moqui-plus-runtime.war"]
ONBUILD RUN groupadd -r opensearch && useradd --no-log-init -r -d /opt/moqui/runtime/opensearch -g opensearch opensearch && chown -R opensearch:opensearch runtime/opensearch

FROM start AS test
EXPOSE $webapp_http_port
EXPOSE $open_search_port
ENTRYPOINT ["gradle", "test"]
CMD ["--info"]

FROM openjdk:11-jre AS builder
ONBUILD WORKDIR /opt/moqui
ONBUILD COPY --from=start /opt/moqui/WEB-INF WEB-INF
ONBUILD COPY --from=start /opt/moqui/META-INF META-INF
ONBUILD COPY --from=start /opt/moqui/*.class ./
ONBUILD COPY --from=start /opt/moqui/execlib execlib
ONBUILD COPY --from=start /opt/moqui/runtime runtime
ONBUILD VOLUME ["/opt/moqui/runtime/conf", "/opt/moqui/runtime/lib", "/opt/moqui/runtime/classes", "/opt/moqui/runtime/ecomponent"]
ONBUILD VOLUME ["/opt/moqui/runtime/log", "/opt/moqui/runtime/txlog", "/opt/moqui/runtime/sessions", "/opt/moqui/runtime/db", "/opt/moqui/runtime/opensearch"]
ONBUILD ENTRYPOINT ["java", "-cp", ".", "MoquiStart"]
ONBUILD HEALTHCHECK --interval=30s --timeout=600ms --start-period=120s CMD curl -f -H "X-Forwarded-Proto: https" -H "X-Forwarded-Ssl: on" http://localhost/status || exit 1

FROM builder as dev
EXPOSE $webapp_http_port
EXPOSE $h2_console_port
EXPOSE $open_search_port
EXPOSE $open_search_cluster_port
CMD ["conf=conf/MoquiDevConf.xml", "port=$webapp_http_port"]

FROM dev AS dev-hazelcast
EXPOSE $hazelcast_port

FROM builder AS production
EXPOSE $webapp_http_port
CMD ["conf=conf/MoquiProductionConf.xml", "port=$webapp_http_port"]

FROM production as production-hazelcast
EXPOSE $hazelcast_port
