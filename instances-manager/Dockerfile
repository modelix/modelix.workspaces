FROM eclipse-temurin:11.0.23_9-jdk@sha256:47e45bcca04ff3e8a2dbcb83effcce4fa983783c3e6198577e6216ff94738414

WORKDIR /instances-manager/
EXPOSE 33332
COPY build/libs/instances-manager-latest-all.jar /instances-manager/
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-XX:MaxRAMPercentage=85", "-jar", "/instances-manager/instances-manager-latest-all.jar"]