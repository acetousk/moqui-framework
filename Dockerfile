# syntax=docker/dockerfile:1

FROM gradle:5.6.4-jdk8 AS builder
WORKDIR /opt/moqui
COPY . ./
RUN gradle getRuntime addRuntime
RUN unzip -qo moqui-plus-runtime.war

FROM builder AS test
CMD ["./gradlew", "load", "test", "--info"]

FROM builder AS dev
EXPOSE 8080
EXPOSE 9200
EXPOSE 9300
ENTRYPOINT ["java", "-jar", "moqui-plus-runtime.war", "port=8080"]
CMD ["conf=conf/MoquiDevConf.xml"]

FROM openjdk:8-jdk AS production
WORKDIR /opt/moqui
COPY --from=builder /opt/moqui/WEB-INF WEB-INF
COPY --from=builder /opt/moqui/META-INF META-INF
COPY --from=builder /opt/moqui/*.class ./
COPY --from=builder /opt/moqui/execlib execlib
COPY --from=builder /opt/moqui/runtime runtime
#VOLUME ["/opt/moqui/runtime/conf", "/opt/moqui/runtime/lib", "/opt/moqui/runtime/classes", "/opt/moqui/runtime/ecomponent"]
#VOLUME ["/opt/moqui/runtime/log", "/opt/moqui/runtime/txlog", "/opt/moqui/runtime/sessions", "/opt/moqui/runtime/db"]
EXPOSE 8080
EXPOSE 9200
EXPOSE 9300
ENTRYPOINT ["java", "-cp", ".", "MoquiStart", "port=8080"]
HEALTHCHECK --interval=30s --timeout=600ms --start-period=120s CMD curl -f -H "X-Forwarded-Proto: https" -H "X-Forwarded-Ssl: on" http://localhost/status || exit 1
CMD ["conf=conf/MoquiProductionConf.xml"]
