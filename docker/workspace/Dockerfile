# Build this administrator-controlled image before enabling command execution.
# Production deployments should pin the base image and configured image by digest.
FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
RUN test -x /usr/bin/timeout && mkdir /workspace && chmod 755 /workspace
ENV HOME=/tmp
WORKDIR /workspace
USER 1000:1000
