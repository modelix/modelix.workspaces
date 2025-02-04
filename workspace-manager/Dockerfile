FROM eclipse-temurin:21.0.6_7-jdk@sha256:4f53cf2ef36a0068a3acc0fa8ddf80548355f4ad880d7f7299fdb77118f8daed

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/

ENV WORKSPACE_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071 -XX:MaxRAMPercentage=75"
CMD ["/workspace-manager/bin/workspace-manager"]
