FROM eclipse-temurin:21.0.4_7-jdk@sha256:49deb4332bb815ce4792c3673936042711b5fe64c75752ff505b591768499c4f

WORKDIR /instances-manager/
EXPOSE 33332
COPY build/libs/instances-manager-latest-all.jar /instances-manager/
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-XX:MaxRAMPercentage=85", "-jar", "/instances-manager/instances-manager-latest-all.jar"]