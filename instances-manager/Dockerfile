FROM eclipse-temurin:21.0.5_11-jdk@sha256:843686b2422d68890bb3ee90c5d08d9b325b9a2acf06ffca42351c2d187d3921

WORKDIR /instances-manager/
EXPOSE 33332

COPY build/install/instances-manager/ /instances-manager/
ENV INSTANCES_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -XX:MaxRAMPercentage=85"
CMD ["/instances-manager/bin/instances-manager"]