FROM eclipse-temurin:11.0.23_9-jdk@sha256:ab693355763a733fdb77ea21c21b9dfad8ca2438e66fba73b4ca1262d9e4e047

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/
CMD ["/workspace-manager/bin/workspace-manager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-XX:MaxRAMPercentage=75"]
