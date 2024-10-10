FROM eclipse-temurin:21.0.4_7-jdk@sha256:2f57163ab32eac18066e6151177fc05a99e4f752979d671f963ed9d5e3ef9ff6

WORKDIR /instances-manager/
EXPOSE 33332
COPY build/libs/instances-manager-latest-all.jar /instances-manager/
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-XX:MaxRAMPercentage=85", "-jar", "/instances-manager/instances-manager-latest-all.jar"]