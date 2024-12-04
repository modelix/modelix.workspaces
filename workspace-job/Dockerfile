FROM debian

RUN apt-get update \
    && apt-get install -y ca-certificates wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=gcr.io/kaniko-project/executor:debug /kaniko/executor /kaniko/executor
RUN mkdir /kaniko/.docker

COPY workspace-job.sh /workspace-job.sh

ENTRYPOINT ["/bin/sh", "-c"]
CMD ["/workspace-job.sh"]
