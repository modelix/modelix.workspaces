FROM gcr.io/kaniko-project/executor:debug@sha256:c3109d5926a997b100c4343944e06c6b30a6804b2f9abe0994d3de6ef92b028e

COPY workspace-job.sh /workspace-job.sh

ENTRYPOINT ["/bin/sh", "-c"]
CMD ["/workspace-job.sh"]
