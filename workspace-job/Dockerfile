FROM ghcr.io/jqlang/jq@sha256:a186dcd84a1e28bb48cdf3d7768b890d08621a87bb651fadb7db6815a6bf5ad5

FROM gcr.io/kaniko-project/executor:debug@sha256:c3109d5926a997b100c4343944e06c6b30a6804b2f9abe0994d3de6ef92b028e

# `jq` will be used in the workspace-job.sh
COPY --from=0 /jq /busybox/jq
COPY workspace-job.sh /workspace-job.sh

ENTRYPOINT ["/bin/sh", "-c"]
CMD ["/workspace-job.sh"]
