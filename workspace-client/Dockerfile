ARG MODELIX_MPS_COMPONENTS_VERSION
FROM modelix/modelix-projector:$MODELIX_MPS_COMPONENTS_VERSION

USER root

COPY build/libs/workspace-client-latest-all.jar /home/projector-user/workspace-client.jar
COPY download-workspace-and-start-projector.sh /

RUN chown -R projector-user:projector-user /home/projector-user/
USER projector-user

WORKDIR /home/projector-user/
CMD ["/download-workspace-and-start-projector.sh"]
