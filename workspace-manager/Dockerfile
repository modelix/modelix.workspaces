FROM openjdk:11

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/
CMD ["/workspace-manager/bin/workspace-manager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-XX:MaxRAMPercentage=75"]
