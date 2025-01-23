FROM eclipse-temurin:21.0.5_11-jdk@sha256:d59ca4960a17035592a5c928343ba56862ea6067929da4e776d7a0f4ec26aa44

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/

ENV WORKSPACE_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071 -XX:MaxRAMPercentage=75"
CMD ["/workspace-manager/bin/workspace-manager"]
