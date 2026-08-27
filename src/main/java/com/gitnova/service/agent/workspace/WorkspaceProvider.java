package com.gitnova.service.agent.workspace;

public interface WorkspaceProvider {
    /** Stable provider discriminator persisted with the Logical Workspace. */
    String providerType();

    WorkspaceHandle provision(WorkspaceSpec trustedSpec);

    /**
     * Opaque durable reference understood by this provider.
     *
     * <p>The local provider currently uses an absolute directory path. A future
     * snapshot/container provider can return its own stable reference without
     * changing Session persistence.</p>
     */
    default String providerReference(WorkspaceHandle handle) {
        return handle.root().toString();
    }
}
