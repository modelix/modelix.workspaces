FROM eclipse-temurin:21.0.4_7-jdk@sha256:eb3a390a3dcf5267960a36c7bdc69f9dc1653b9d623f7cd371784fd06f43a217

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/
CMD ["/workspace-manager/bin/workspace-manager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-XX:MaxRAMPercentage=75"]
