package com.gitnova.service.agent.workspace;

import com.gitnova.storage.RepoKey;

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
        public LocalWorkspaceState(
                WorkspaceId workspaceId,
                RepoKey repoKey,
                SnapshotScope source,
                Path root,
                long generation,
                String contentFingerprint
        ) {
            this.workspaceId=Objects.requireNonNull(workspaceId,"workspaceId must not be null");
            this.repoKey=Objects.requireNonNull(repoKey,"repoKey must not be null");
            this.source=Objects.requireNonNull(source,"source must not be null");
            this.root=Objects.requireNonNull(root,"root must not be null").toAbsolutePath().normalize();
            lock=new ReentrantReadWriteLock(true);
            if(generation<0) throw new IllegalArgumentException("generation must not be negative");
            this.generation=generation;
            this.contentFingerprint = Objects.requireNonNull(
                    contentFingerprint,
                    "contentFingerprint must not be null"
            );
            this.latestSuccessfulValidationGeneration=null;
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
    }
    private final ConcurrentHashMap<WorkspaceId,LocalWorkspaceState>states;
    public LocalWorkspaceRegistry(){
        this.states=new ConcurrentHashMap<>();
    }
    public void register(WorkspaceHandle handle){
        Objects.requireNonNull(handle,"handle must not be null");
        String contentFingerprint = WorkspaceTreeFingerprint.capture(handle.root());
        LocalWorkspaceState state = new LocalWorkspaceState(
                handle.workspaceId(),
                handle.repoKey(),
                handle.source(),
                handle.root(),
                handle.generation(),
                contentFingerprint
        );
        LocalWorkspaceState previous=states.putIfAbsent(handle.workspaceId(),state);
        if(previous!=null){
            throw new IllegalStateException(
                    "Workspace already registered: " + handle.workspaceId()
            );
        }
    }
    LocalWorkspaceState require(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        LocalWorkspaceState state = states.get(workspaceId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown workspace: " + workspaceId);
        }
        return state;
    }
}
