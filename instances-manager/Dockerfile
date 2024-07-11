FROM eclipse-temurin:11.0.23_9-jdk@sha256:ab693355763a733fdb77ea21c21b9dfad8ca2438e66fba73b4ca1262d9e4e047

WORKDIR /instances-manager/
EXPOSE 33332
COPY build/libs/instances-manager-latest-all.jar /instances-manager/
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-XX:MaxRAMPercentage=85", "-jar", "/instances-manager/instances-manager-latest-all.jar"]