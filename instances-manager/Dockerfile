FROM eclipse-temurin:21.0.3_9-jdk@sha256:3d92c3222b9d376e420e7099054604dc0d7bafbebbcb4f79ffd77763cd6a1bcd

WORKDIR /instances-manager/
EXPOSE 33332
COPY build/libs/instances-manager-latest-all.jar /instances-manager/
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-XX:MaxRAMPercentage=85", "-jar", "/instances-manager/instances-manager-latest-all.jar"]