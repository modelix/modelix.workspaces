# Modelix Workspaces

Modelix Workspaces are components to run MPS in with Kubernetes.

It provides facilities to configure and provision MPS instances in Kubernetes.
Users can use a provisioned MPS through the browser.
This is implemented with [JetBrains Projector](https://lp.jetbrains.com/projector/).

Further users of a workspace can collaborate in real-time.
Real-Time collaboration implemented with the [Modelix Model Server](https://docs.modelix.org/modelix/main/core/reference/component-model-server.html) and the [Model Sync Plugin](https://github.com/modelix/modelix.mps/tree/mps/2020.3/mps/org.modelix.model.mpsplugin).

## Usage

To deploy Modelix Workspaces [follow the instructions in the Helm chart repository](https://github.com/modelix/modelix.kubernetes?tab=readme-ov-file#making-changes-to-modelix-workspace-components).


## Development

This project uses Gradle to build code and a Shell script to build OCI images.

Developing and testing and testing it locally is currently tightly coupled with the [Helm chart for Modelix Workspaces](https://github.com/modelix/modelix.kubernetes).

1. Set up the project by running:
   ```shell
   ./gradlew
   ```
2. Build your changes by running:
   ```shell
   ./gradlew build
   ```
3. Build OCI images with Docker by running:
   ```shell
   ./docker-build-local-and-publish-on-ci-all.sh
   ```
4. To run the locally built images, follow the instructions in [Helm chart for Modelix Workspaces](https://github.com/modelix/modelix.kubernetes?tab=readme-ov-file#making-changes-to-modelix-workspace-components).  
   You can see the version of the local images in their label or the [version.txt](version.txt).

## Publishing

Commits published to `main` automatically trigger a new release.