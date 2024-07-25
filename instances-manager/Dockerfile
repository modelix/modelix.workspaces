FROM eclipse-temurin:21.0.4_7-jdk@sha256:b18dabf509aeeb700d9525cdebf6bdbededb06536c6e233a3a21e6fb04d2be8c

WORKDIR /instances-manager/
EXPOSE 33332
COPY build/libs/instances-manager-latest-all.jar /instances-manager/
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-XX:MaxRAMPercentage=85", "-jar", "/instances-manager/instances-manager-latest-all.jar"]