FROM eclipse-temurin:21.0.4_7-jdk@sha256:2f57163ab32eac18066e6151177fc05a99e4f752979d671f963ed9d5e3ef9ff6

RUN apt update && apt install -y git

WORKDIR /workspace-manager/
EXPOSE 28104
COPY build/install/workspace-manager/ /workspace-manager/
CMD ["/workspace-manager/bin/workspace-manager", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-XX:MaxRAMPercentage=75"]
