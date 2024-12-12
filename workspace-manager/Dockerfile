FROM eclipse-temurin:21.0.5_11-jdk@sha256:843686b2422d68890bb3ee90c5d08d9b325b9a2acf06ffca42351c2d187d3921

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/

ENV WORKSPACE_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071 -XX:MaxRAMPercentage=75"
CMD ["/workspace-manager/bin/workspace-manager"]
