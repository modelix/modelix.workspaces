FROM eclipse-temurin:21.0.4_7-jdk@sha256:eb3a390a3dcf5267960a36c7bdc69f9dc1653b9d623f7cd371784fd06f43a217

WORKDIR /instances-manager/
EXPOSE 33332
COPY build/libs/instances-manager-latest-all.jar /instances-manager/
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-XX:MaxRAMPercentage=85", "-jar", "/instances-manager/instances-manager-latest-all.jar"]