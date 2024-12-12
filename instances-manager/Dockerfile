FROM eclipse-temurin:21.0.5_11-jdk@sha256:843686b2422d68890bb3ee90c5d08d9b325b9a2acf06ffca42351c2d187d3921

WORKDIR /instances-manager/
EXPOSE 33332
COPY build/libs/instances-manager-latest-all.jar /instances-manager/
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-XX:MaxRAMPercentage=85", "-jar", "/instances-manager/instances-manager-latest-all.jar"]