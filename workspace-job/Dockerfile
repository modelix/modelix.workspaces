ARG JAVA_IMAGE
FROM $JAVA_IMAGE

RUN apt update && apt install -y maven git ant

WORKDIR /workspace-job/
COPY build/install/workspace-job /workspace-job/

ARG MPS_MAJOR_VERSION
COPY build/mps$MPS_MAJOR_VERSION /mps

#COPY build/mps$MPS_MAJOR_VERSION/artifacts/de.itemis.mps.extensions /languages/mps-extensions
#COPY build/mps$MPS_MAJOR_VERSION/artifacts/org.modelix /languages/modelix/plugins

CMD ["/workspace-job/bin/workspace-job", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071", "-Dmps.home=/mps", "-XX:MaxRAMPercentage=75"]
