# Build on the execution Engine. Intel Windows: docker build --platform linux/amd64 ...
# Development build template; record the resolved base digest before release.
FROM eclipse-temurin:21-jdk-jammy
USER root
RUN apt-get update && apt-get install -y --no-install-recommends bash coreutils sed findutils util-linux ripgrep python3 maven diffutils ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && (getent group 1000 >/dev/null || groupadd -g 1000 agent) \
 && (getent passwd 1000 >/dev/null || useradd -u 1000 -g 1000 -m agent) \
 && install -d -o 1000 -g 1000 /session /session/worktree /session/state /session/exports /session/bootstrap /session/baseline /opt/gitnova
COPY agent-runtime/agent-worker/target/agent-worker.jar /opt/gitnova/agent-worker.jar
COPY deploy/agent/worker-entrypoint.sh /opt/gitnova/worker-entrypoint.sh
COPY deploy/agent/process-launch.py /opt/gitnova/process-launch.py
RUN chmod 0555 /opt/gitnova/worker-entrypoint.sh /opt/gitnova/process-launch.py
USER 1000:1000
WORKDIR /session/worktree
ENV GITNOVA_SESSION_ROOT=/session GITNOVA_WORKER_PORT=8081 LANG=C.UTF-8
ENTRYPOINT ["/opt/gitnova/worker-entrypoint.sh"]
