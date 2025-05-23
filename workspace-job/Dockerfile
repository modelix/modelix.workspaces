FROM ghcr.io/jqlang/jq@sha256:a186dcd84a1e28bb48cdf3d7768b890d08621a87bb651fadb7db6815a6bf5ad5

FROM gcr.io/kaniko-project/executor:debug@sha256:2562c4fe551399514277ffff7dcca9a3b1628c4ea38cb017d7286dc6ea52f4cd

# `jq` will be used in the workspace-job.sh
COPY --from=0 /jq /busybox/jq
COPY workspace-job.sh /workspace-job.sh

ENTRYPOINT ["/bin/sh", "-c"]
CMD ["/workspace-job.sh"]
