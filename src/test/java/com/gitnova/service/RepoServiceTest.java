package com.gitnova.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.entity.Repository;
import com.gitnova.mapper.RepoMemberMapper;
import com.gitnova.mapper.RepositoryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoServiceTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void shouldDelegateRepositoryDetailAuthorization() {
        Dependencies dependencies = dependencies();
        Repository repository = repository();
        UserContext.setUserId(7L);
        when(dependencies.repositoryAccessService.requireReadAccess(42L, 7L))
                .thenReturn(repository);

        ApiResponse<?> response = dependencies.service.getRepoDetail(42L);

        assertEquals(200, response.getCode());
        assertSame(repository, response.getData());
        verify(dependencies.repositoryAccessService).requireReadAccess(42L, 7L);
    }

    @Test
    void shouldAuthorizeOwnerBeforeDeleteAndEvictAfterCommit() {
        Dependencies dependencies = dependencies();
        UserContext.setUserId(7L);
        when(dependencies.repositoryAccessService.requireOwnerAccess(42L, 7L))
                .thenReturn(repository());
        when(dependencies.repoMemberMapper.delete(any(LambdaQueryWrapper.class)))
                .thenReturn(2);
        when(dependencies.repositoryMapper.deleteById(42L)).thenReturn(1);

        ApiResponse<?> response = dependencies.service.deleteRepo(42L);

        assertEquals(200, response.getCode());
        verify(dependencies.repositoryAccessService).requireOwnerAccess(42L, 7L);
        verify(dependencies.repositoryAccessService).evictMemberAfterCommit(42L, 7L);
        verify(dependencies.repoMemberMapper).delete(any(LambdaQueryWrapper.class));
        verify(dependencies.repositoryMapper).deleteById(42L);
    }

    private static Dependencies dependencies() {
        RepositoryMapper repositoryMapper = mock(RepositoryMapper.class);
        RepoMemberMapper repoMemberMapper = mock(RepoMemberMapper.class);
        GitletService gitletService = mock(GitletService.class);
        RepositoryAccessService repositoryAccessService = mock(
                RepositoryAccessService.class
        );
        return new Dependencies(
                repositoryMapper,
                repoMemberMapper,
                repositoryAccessService,
                new RepoService(
                        repositoryMapper,
                        repoMemberMapper,
                        gitletService,
                        repositoryAccessService
                )
        );
    }

    private static Repository repository() {
        Repository repository = new Repository();
        repository.setId(42L);
        repository.setOwnerId(7L);
        repository.setIsPrivate(1);
        return repository;
    }

    private record Dependencies(
            RepositoryMapper repositoryMapper,
            RepoMemberMapper repoMemberMapper,
            RepositoryAccessService repositoryAccessService,
            RepoService service
    ) {
    }
}
