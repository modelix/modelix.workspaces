FROM eclipse-temurin:21.0.6_7-jdk@sha256:b37f7053b1b366c62bd8d332508852a1281b59656af5bd6cf27a008d91111214

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/

ENV WORKSPACE_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071 -XX:MaxRAMPercentage=75"
CMD ["/workspace-manager/bin/workspace-manager"]
