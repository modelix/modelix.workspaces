FROM eclipse-temurin:21.0.4_7-jdk@sha256:55725e4e1531b5816284acb94eae597fc668683741ef3514eca9b94f6f7b8696

WORKDIR /instances-manager/
EXPOSE 33332
COPY build/libs/instances-manager-latest-all.jar /instances-manager/
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-XX:MaxRAMPercentage=85", "-jar", "/instances-manager/instances-manager-latest-all.jar"]