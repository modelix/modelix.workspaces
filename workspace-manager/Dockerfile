FROM eclipse-temurin:21.0.3_9-jdk@sha256:3d92c3222b9d376e420e7099054604dc0d7bafbebbcb4f79ffd77763cd6a1bcd

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/
CMD ["/workspace-manager/bin/workspace-manager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-XX:MaxRAMPercentage=75"]
