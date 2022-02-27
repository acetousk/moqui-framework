# syntax=docker/dockerfile:1

FROM gradle:5.6.4-jdk8 AS builder
WORKDIR /opt/moqui
COPY . ./
RUN gradle getRuntime addRuntime
RUN unzip -qo moqui-plus-runtime.war

FROM openjdk:8-jdk AS test
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
EXPOSE 5601
ENTRYPOINT ["java", "-cp", ".", "MoquiStart", "port=8080"]
HEALTHCHECK --interval=30s --timeout=600ms --start-period=120s CMD curl -f -H "X-Forwarded-Proto: https" -H "X-Forwarded-Ssl: on" http://localhost/status || exit 1
CMD ["conf=conf/MoquiDevConf.xml"]
