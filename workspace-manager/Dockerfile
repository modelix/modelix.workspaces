FROM eclipse-temurin:21.0.4_7-jdk@sha256:b18dabf509aeeb700d9525cdebf6bdbededb06536c6e233a3a21e6fb04d2be8c

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/
CMD ["/workspace-manager/bin/workspace-manager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-XX:MaxRAMPercentage=75"]
