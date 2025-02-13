FROM eclipse-temurin:21.0.6_7-jdk@sha256:0b31d935d2db37bc4428aafddd8af48d8ec75e8f61d2f7546c037d5e4d967fc9

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/

ENV WORKSPACE_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071 -XX:MaxRAMPercentage=75"
CMD ["/workspace-manager/bin/workspace-manager"]
