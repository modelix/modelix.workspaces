FROM eclipse-temurin:21.0.5_11-jdk@sha256:d59ca4960a17035592a5c928343ba56862ea6067929da4e776d7a0f4ec26aa44

WORKDIR /instances-manager/
EXPOSE 33332

COPY build/install/instances-manager/ /instances-manager/
ENV instances_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -XX:MaxRAMPercentage=85"
CMD ["/instances-manager/bin/instances-manager"]