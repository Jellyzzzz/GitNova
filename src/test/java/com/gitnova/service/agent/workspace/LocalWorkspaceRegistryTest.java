package com.gitnova.service.agent.workspace;

import com.gitnova.entity.agent.AgentWorkspaceEntity;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.storage.RepoKey;
import com.gitnova.storage.config.WorkspaceStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalWorkspaceRegistryTest {

    @Mock
    AgentWorkspaceMapper workspaceMapper;

    @TempDir
    Path tempDir;

    @Test
    void shouldLazilyRehydrateAReadyWorkspaceAfterProcessRestart() throws Exception {
        WorkspaceId workspaceId = WorkspaceId.generate();
        Path root = Files.createDirectories(tempDir.resolve(workspaceId.toString()));
        Files.writeString(root.resolve("README.md"), "ready\n");

        AgentWorkspaceEntity persisted = new AgentWorkspaceEntity();
        persisted.setWorkspaceId(workspaceId.toString());
        persisted.setRepoKey(RepoKey.of(7L, 42L).value());
        persisted.setBaseRevision("a".repeat(40));
        persisted.setProviderType("local-filesystem");
        persisted.setProviderRef(root.toString());
        persisted.setGeneration(3L);
        persisted.setContentFingerprint(WorkspaceTreeFingerprint.capture(root));
        persisted.setWriterRunId("run-3");
        persisted.setLastAcceptedFencingToken(3L);
        when(workspaceMapper.selectReadyForRegistration(workspaceId.toString()))
                .thenReturn(persisted);

        LocalWorkspaceRegistry registry = new LocalWorkspaceRegistry(
                workspaceMapper,
                new WorkspaceStorageProperties(tempDir)
        );
        LocalWorkspaceRegistry.LocalWorkspaceState first = registry.require(workspaceId);
        LocalWorkspaceRegistry.LocalWorkspaceState second = registry.require(workspaceId);

        assertSame(first, second);
        assertEquals(root, first.root());
        assertEquals(3L, first.generation());
        assertEquals(RepoKey.of(7L, 42L), first.repoKey());
        verify(workspaceMapper).selectReadyForRegistration(workspaceId.toString());
    }
}
