FROM eclipse-temurin:21.0.6_7-jdk@sha256:1d70e8e91f3916f56bd73581f6ba293077490a7ac273af2afead66cfecfee7cf

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/

ENV WORKSPACE_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071 -XX:MaxRAMPercentage=75"
CMD ["/workspace-manager/bin/workspace-manager"]
