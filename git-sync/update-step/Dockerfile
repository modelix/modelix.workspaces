ARG JAVA_IMAGE
FROM $JAVA_IMAGE

WORKDIR /update-step
COPY build/install/update-step /update-step/
COPY build/mpsDependencies /mpsDependencies

COPY --from=mps-context / /mps

CMD ["/update-step/bin/update-step", "-Dmps.home=/mps", "-XX:MaxRAMPercentage=75"]
