FROM eclipse-temurin:21.0.4_7-jdk@sha256:49deb4332bb815ce4792c3673936042711b5fe64c75752ff505b591768499c4f

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/
CMD ["/workspace-manager/bin/workspace-manager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-XX:MaxRAMPercentage=75"]
