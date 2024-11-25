FROM eclipse-temurin:21.0.5_11-jdk@sha256:5ad4efff3364b06c61578b267138359bcba92acc20dfd533f35b75c709a6f10b

WORKDIR /instances-manager/
EXPOSE 33332

COPY build/install/instances-manager/ /instances-manager/
ENV instances_MANAGER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -XX:MaxRAMPercentage=85"
CMD ["/instances-manager/bin/instances-manager"]