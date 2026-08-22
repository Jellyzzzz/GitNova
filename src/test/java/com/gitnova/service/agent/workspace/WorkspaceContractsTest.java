package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectId;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceContractsTest {

    private static final String SHA1 = "a".repeat(40);

    @Test
    void shouldGenerateAndParseOpaqueWorkspaceIds() {
        WorkspaceId first = WorkspaceId.generate();
        WorkspaceId second = WorkspaceId.generate();

        assertNotEquals(first, second);
        assertEquals(first, WorkspaceId.parse(first.toString()));
        assertThrows(NullPointerException.class, () -> WorkspaceId.parse(null));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceId.parse("../escape"));
    }

    @Test
    void shouldKeepTypedSnapshotAndRepositorySource() {
        WorkspaceId workspaceId = WorkspaceId.generate();
        RepoKey repoKey = RepoKey.of(7L, 42L);
        SnapshotScope scope = SnapshotScope.of(SHA1);

        WorkspaceSpec spec = new WorkspaceSpec(workspaceId, repoKey, scope);

        assertEquals(workspaceId, spec.workspaceId());
        assertEquals(repoKey, spec.repoKey());
        assertEquals(GitObjectId.of(SHA1), spec.snapshotScope().baseSha1());
        assertThrows(IllegalArgumentException.class, () -> SnapshotScope.of("not-a-sha"));
    }

    @Test
    void shouldNormalizeInternalRootAndRejectNegativeGeneration() {
        WorkspaceId workspaceId = WorkspaceId.generate();
        RepoKey repoKey = RepoKey.of(7L, 42L);
        SnapshotScope scope = SnapshotScope.of(SHA1);
        Path relativeRoot = Path.of("build", "workspace-contract", "..", "ws");

        WorkspaceHandle handle = new WorkspaceHandle(
                workspaceId,
                repoKey,
                scope,
                relativeRoot,
                WorkspaceStatus.READY,
                0
        );

        assertTrue(handle.root().isAbsolute());
        assertEquals(relativeRoot.toAbsolutePath().normalize(), handle.root());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceHandle(
                        workspaceId,
                        repoKey,
                        scope,
                        relativeRoot,
                        WorkspaceStatus.READY,
                        -1
                )
        );
    }
}
