package com.gitnova.service.session;

import com.gitnova.mapper.BranchMapper;
import com.gitnova.service.agent.workspace.SnapshotScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryRevisionServiceTest {

    private static final String HEAD = "a".repeat(40);

    @Test
    void shouldResolveTheDefaultBranchToAnImmutableSnapshot() {
        BranchMapper branchMapper = mock(BranchMapper.class);
        when(branchMapper.findHead(42L, "main")).thenReturn(HEAD);
        RepositoryRevisionService service = new RepositoryRevisionService(branchMapper);

        SnapshotScope result = service.requireBranchSnapshot(42L, null);

        assertEquals(HEAD, result.baseSha1().value());
        verify(branchMapper).findHead(42L, "main");
    }

    @Test
    void shouldRejectAMissingBranch() {
        BranchMapper branchMapper = mock(BranchMapper.class);
        when(branchMapper.findHead(42L, "feature/session")).thenReturn(null);
        RepositoryRevisionService service = new RepositoryRevisionService(branchMapper);

        RepositoryRevisionService.BranchNotFoundException exception = assertThrows(
                RepositoryRevisionService.BranchNotFoundException.class,
                () -> service.requireBranchSnapshot(42L, "feature/session")
        );

        assertEquals("feature/session", exception.branchName());
    }

    @Test
    void shouldRejectAnInvalidPersistedHead() {
        BranchMapper branchMapper = mock(BranchMapper.class);
        when(branchMapper.findHead(42L, "main")).thenReturn("invalid-head");
        RepositoryRevisionService service = new RepositoryRevisionService(branchMapper);

        assertThrows(
                IllegalStateException.class,
                () -> service.requireBranchSnapshot(42L, "main")
        );
    }
}
