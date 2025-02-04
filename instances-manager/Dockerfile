FROM eclipse-temurin:21.0.6_7-jdk@sha256:4f53cf2ef36a0068a3acc0fa8ddf80548355f4ad880d7f7299fdb77118f8daed

WORKDIR /instances-manager/
EXPOSE 33332

COPY build/install/instances-manager/ /instances-manager/
ENV instances_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -XX:MaxRAMPercentage=85"
CMD ["/instances-manager/bin/instances-manager"]