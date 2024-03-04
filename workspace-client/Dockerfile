ARG MPS_VERSION
# If the version of modelix/projector-mps is missing, run docker-build-projector-mps.sh to build and publish that image
FROM modelix/projector-mps:$MPS_VERSION AS with-zip
USER root
RUN apt-get update  \
    && apt-get install unzip zip wget -y  \
    && rm -rf /var/lib/apt/lists/* \
    && rm -rf /var/cache/apt

FROM with-zip AS patch-branding

COPY helper/patch-branding.sh /
RUN /patch-branding.sh

FROM with-zip

COPY --from=patch-branding /branding.zip /projector/ide/lib/branding.jar

RUN echo "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5071" >> /projector/ide/bin/mps64.vmoptions \
    && sed -i.bak '/-Xmx/d' /projector/ide/bin/mps64.vmoptions \
    && echo "-XX:MaxRAMPercentage=85" >> /projector/ide/bin/mps64.vmoptions \
    && mkdir -p /mps-plugins \
    && mkdir -p /mps-languages \
    && chown -R projector-user:projector-user /home/projector-user \
    && chown -R projector-user:projector-user /mps-plugins \
    && chown -R projector-user:projector-user /mps-languages \
    && chown -R projector-user:projector-user /projector/ide/

# An "End User Agreement" dialog prevents the startup if the vendor name is 'JetBrains'
# See
# - https://github.com/JetBrains/intellij-community/blob/777669cc01eb14e6fcf2ed3ba11d2c1d3832d6e2/platform/platform-impl/src/com/intellij/idea/eua.kt#L19-L20
# - https://github.com/JetBrains/MPS/blob/418307944be761dd1e62af65881c8eade086386f/plugins/mps-build/solutions/mpsBuild/source_gen/jetbrains/mps/ide/build/mps.sh#L224
# - https://github.com/JetBrains/MPS/blob/418307944be761dd1e62af65881c8eade086386f/plugins/mps-build/solutions/mpsBuild/source_gen/jetbrains/mps/ide/build/mps.sh#L57
#
# This way of setting the vendor name is only available in newer MPS versions.
# For older versions the patch-branding.sh script is still required.
RUN sed -i.bak "s/IDEA_VENDOR_NAME='JetBrains'/IDEA_VENDOR_NAME='Modelix'/g" /projector/ide/bin/mps.sh

USER projector-user

COPY --chown=projector-user:projector-user helper/projector-user-home /home/projector-user

# rename config directory to match the correct MPS version
RUN mv "/home/projector-user/.config/JetBrains/MPSxxxx.x" "/home/projector-user/.config/JetBrains/MPS$(grep "mpsBootstrapCore.version=" /projector/ide/build.properties|cut -d'=' -f2)"

# changing the vendor name (see above) also changes the location of the config dir
RUN ln -s /home/projector-user/.config/JetBrains /home/projector-user/.config/Modelix

COPY --chown=projector-user:projector-user helper/log.xml /projector/ide/bin/log.xml

COPY helper/install-plugins.sh /
RUN /install-plugins.sh /projector/ide/plugins/

USER root

COPY build/libs/workspace-client-latest-all.jar /home/projector-user/workspace-client.jar
COPY download-workspace-and-start-projector.sh /

RUN chown -R projector-user:projector-user /home/projector-user/

#RUN rm -rf /projector/ide/plugins/org.modelix.*
#RUN rm -rf /projector/ide/plugins/com.mbeddr.*
#RUN rm -rf /projector/ide/plugins/com.dslfoundry.*
#RUN rm -rf /projector/ide/plugins/de.itemis.*
#RUN rm -rf /projector/ide/plugins/de-itemis-*
#RUN rm -rf /projector/ide/plugins/de.q60.*
#RUN rm -rf /projector/ide/plugins/de.slisson.*
#RUN rm -rf /projector/ide/plugins/mps-math-editor
#RUN rm -rf /projector/ide/plugins/mps-multiline
#RUN rm -rf /projector/ide/plugins/mps-richtext
#RUN rm -rf /projector/ide/plugins/mps-tables

# <editor-fold desc="yourkit">
# https://www.yourkit.com/docs/java-profiler/2023.9/help/docker_direct.jsp

#RUN wget https://www.yourkit.com/download/docker/YourKit-JavaProfiler-2023.9-docker.zip -P /tmp/ && \
#  unzip /tmp/YourKit-JavaProfiler-2023.9-docker.zip -d /usr/local && \
#  rm /tmp/YourKit-JavaProfiler-2023.9-docker.zip
#RUN echo "-agentpath:/usr/local/YourKit-JavaProfiler-2023.9/bin/linux-x86-64/libyjpagent.so=port=10001,listen=all" >> /projector/ide/bin/mps64.vmoptions

# </editor-fold>

COPY --chown=projector-user:projector-user build/mps-plugins /projector/ide/plugins

USER projector-user

WORKDIR /home/projector-user/
CMD ["/download-workspace-and-start-projector.sh"]
