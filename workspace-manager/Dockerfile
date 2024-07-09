FROM eclipse-temurin:11.0.23_9-jdk@sha256:47e45bcca04ff3e8a2dbcb83effcce4fa983783c3e6198577e6216ff94738414

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/
CMD ["/workspace-manager/bin/workspace-manager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-XX:MaxRAMPercentage=75"]
