FROM eclipse-temurin:21.0.4_7-jdk@sha256:55725e4e1531b5816284acb94eae597fc668683741ef3514eca9b94f6f7b8696

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/
CMD ["/workspace-manager/bin/workspace-manager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-XX:MaxRAMPercentage=75"]
