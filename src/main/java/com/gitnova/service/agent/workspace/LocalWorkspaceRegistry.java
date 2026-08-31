package com.gitnova.service.agent.workspace;

import com.gitnova.entity.agent.AgentWorkspaceEntity;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.storage.RepoKey;
import com.gitnova.storage.config.WorkspaceStorageProperties;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class LocalWorkspaceRegistry {
    static final class LocalWorkspaceState {
        private final WorkspaceId workspaceId;
        private final RepoKey repoKey;
        private final SnapshotScope source;
        private final Path root;
        private final ReentrantReadWriteLock lock;
        private long generation;
        private String contentFingerprint;
        private Long latestSuccessfulValidationGeneration;
        private String writerRunId;
        private long lastAcceptedFencingToken;

        private LocalWorkspaceState(
                WorkspaceId workspaceId,
                RepoKey repoKey,
                SnapshotScope source,
                Path root,
                long generation,
                String contentFingerprint,
                String writerRunId,
                long lastAcceptedFencingToken
        ) {
            this.workspaceId = Objects.requireNonNull(
                    workspaceId,
                    "workspaceId must not be null"
            );
            this.repoKey = Objects.requireNonNull(repoKey, "repoKey must not be null");
            this.source = Objects.requireNonNull(source, "source must not be null");
            this.root = Objects.requireNonNull(root, "root must not be null")
                    .toAbsolutePath()
                    .normalize();
            this.lock = new ReentrantReadWriteLock(true);
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            this.generation = generation;
            this.contentFingerprint = Objects.requireNonNull(
                    contentFingerprint,
                    "contentFingerprint must not be null"
            );
            this.latestSuccessfulValidationGeneration = null;
            if (lastAcceptedFencingToken < 0) {
                throw new IllegalArgumentException(
                        "lastAcceptedFencingToken must not be negative"
                );
            }
            this.writerRunId = writerRunId;
            this.lastAcceptedFencingToken = lastAcceptedFencingToken;
        }
        WorkspaceId workspaceId() {
            return workspaceId;
        }

        RepoKey repoKey() {
            return repoKey;
        }

        SnapshotScope source() {
            return source;
        }

        Path root() {
            return root;
        }

        ReentrantReadWriteLock lock() {
            return lock;
        }

        long generation() {
            return generation;
        }

        void advanceGeneration() {
            generation = Math.incrementExact(generation);
            latestSuccessfulValidationGeneration = null;
        }

        String contentFingerprint() {
            return contentFingerprint;
        }

        /** Caller must hold this Workspace's write lock. */
        boolean refreshFingerprint(String observedFingerprint) {
            Objects.requireNonNull(observedFingerprint, "observedFingerprint must not be null");
            if (contentFingerprint == null) {
                // An earlier mutation already advanced generation conservatively when its
                // final tree could not be observed. Re-establish the baseline without
                // counting the same unknown mutation a second time.
                contentFingerprint = observedFingerprint;
                return false;
            }
            if (contentFingerprint.equals(observedFingerprint)) {
                return false;
            }
            advanceGeneration();
            contentFingerprint = observedFingerprint;
            return true;
        }

        /** Caller must hold this Workspace's write lock. */
        void acceptFingerprint(String observedFingerprint) {
            contentFingerprint = Objects.requireNonNull(
                    observedFingerprint,
                    "observedFingerprint must not be null"
            );
        }

        /** Caller must hold this Workspace's write lock. */
        void forgetFingerprint() {
            contentFingerprint = null;
        }

        Long latestSuccessfulValidationGeneration() {
            return latestSuccessfulValidationGeneration;
        }

        void markValidationSucceeded(long validatedGeneration) {
            if (validatedGeneration != generation) {
                throw new IllegalStateException(
                        "cannot mark validation for a stale workspace generation"
                );
            }
            latestSuccessfulValidationGeneration = validatedGeneration;
        }

        /** Caller must hold this Workspace's write lock. */
        boolean acceptExecutionPermit(WorkspaceExecutionPermit permit) {
            Objects.requireNonNull(permit, "executionPermit must not be null");
            if (!workspaceId.equals(permit.workspaceId())) {
                throw new IllegalArgumentException(
                        "executionPermit belongs to a different Workspace"
                );
            }
            if (permit.fencingToken() < lastAcceptedFencingToken) {
                return false;
            }
            if (permit.fencingToken() == lastAcceptedFencingToken) {
                return Objects.equals(writerRunId, permit.runId());
            }
            writerRunId = permit.runId();
            lastAcceptedFencingToken = permit.fencingToken();
            return true;
        }

        /** Caller must hold this Workspace's write lock and provider mutation lock. */
        void restoreAcceptedFence(String acceptedRunId, long acceptedFencingToken) {
            if (acceptedFencingToken < lastAcceptedFencingToken) {
                return;
            }
            if (acceptedFencingToken == lastAcceptedFencingToken) {
                if (acceptedFencingToken > 0
                        && writerRunId != null
                        && !writerRunId.equals(acceptedRunId)) {
                    throw new IllegalStateException(
                            "Workspace fence is bound to conflicting Run identities"
                    );
                }
                if (writerRunId == null) {
                    writerRunId = acceptedRunId;
                }
                return;
            }
            writerRunId = acceptedRunId;
            lastAcceptedFencingToken = acceptedFencingToken;
        }
    }

    private final ConcurrentHashMap<WorkspaceId, LocalWorkspaceState> states;
    private final AgentWorkspaceMapper workspaceMapper;
    private final Path workspaceBase;

    public LocalWorkspaceRegistry() {
        this.states = new ConcurrentHashMap<>();
        this.workspaceMapper = null;
        this.workspaceBase = null;
    }

    public LocalWorkspaceRegistry(
            AgentWorkspaceMapper workspaceMapper,
            WorkspaceStorageProperties storageProperties
    ) {
        this.states = new ConcurrentHashMap<>();
        this.workspaceMapper = Objects.requireNonNull(
                workspaceMapper,
                "workspaceMapper must not be null"
        );
        Objects.requireNonNull(storageProperties, "storageProperties must not be null");
        this.workspaceBase = storageProperties.basePath().toAbsolutePath().normalize();
    }

    public void register(WorkspaceHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        String contentFingerprint = WorkspaceTreeFingerprint.capture(handle.root());
        LocalWorkspaceState state = new LocalWorkspaceState(
                handle.workspaceId(),
                handle.repoKey(),
                handle.source(),
                handle.root(),
                handle.generation(),
                contentFingerprint,
                null,
                0
        );
        LocalWorkspaceState previous = states.putIfAbsent(handle.workspaceId(), state);
        if (previous != null
                && (!previous.repoKey().equals(handle.repoKey())
                || !previous.source().equals(handle.source())
                || !previous.root().equals(handle.root()))) {
            throw new IllegalStateException(
                    "Workspace is already registered with different metadata: "
                            + handle.workspaceId()
            );
        }
    }

    LocalWorkspaceState require(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        LocalWorkspaceState state = states.get(workspaceId);
        if (state != null) {
            return state;
        }
        if (workspaceMapper == null) {
            throw new IllegalArgumentException("Unknown workspace: " + workspaceId);
        }

        AgentWorkspaceEntity workspace = workspaceMapper.selectReadyForRegistration(
                workspaceId.toString()
        );
        if (workspace == null) {
            throw new IllegalArgumentException("Unknown active Workspace: " + workspaceId);
        }
        if (!"local-filesystem".equals(workspace.getProviderType())) {
            throw new IllegalStateException(
                    "Workspace provider is not supported by LocalWorkspaceGateway"
            );
        }

        Path root = Path.of(workspace.getProviderRef()).toAbsolutePath().normalize();
        if (root.equals(workspaceBase) || !root.startsWith(workspaceBase)) {
            throw new IllegalStateException(
                    "Persisted Workspace path escapes the configured Workspace root"
            );
        }
        LocalWorkspaceState loaded = new LocalWorkspaceState(
                workspaceId,
                RepoKey.parseCanonical(workspace.getRepoKey()),
                SnapshotScope.of(workspace.getBaseRevision()),
                root,
                Objects.requireNonNull(workspace.getGeneration(), "generation must be persisted"),
                Objects.requireNonNull(
                        workspace.getContentFingerprint(),
                        "contentFingerprint must be persisted"
                ),
                workspace.getWriterRunId(),
                Objects.requireNonNull(
                        workspace.getLastAcceptedFencingToken(),
                        "lastAcceptedFencingToken must be persisted"
                )
        );
        LocalWorkspaceState concurrent = states.putIfAbsent(workspaceId, loaded);
        return concurrent == null ? loaded : concurrent;
    }
}
