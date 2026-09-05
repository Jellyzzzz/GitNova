package com.gitnova.service;

import com.gitnova.entity.Repository;
import com.gitnova.mapper.RepoMemberMapper;
import com.gitnova.mapper.RepositoryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RepositoryAccessServiceTest {

    private static final Long REPO_ID = 42L;
    private static final long ACTOR_ID = 7L;
    private static final String CACHE_KEY = "gitnova:repo-access:42:7";

    private RepositoryMapper repositoryMapper;
    private RepoMemberMapper repoMemberMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RepositoryAccessService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repositoryMapper = mock(RepositoryMapper.class);
        repoMemberMapper = mock(RepoMemberMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new RepositoryAccessService(
                repositoryMapper,
                repoMemberMapper,
                redisTemplate
        );
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldReadPublicRepositoryWithoutMembershipLookup() {
        Repository repository = repository(false);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);

        Repository actual = service.requireReadAccess(REPO_ID, ACTOR_ID);

        assertSame(repository, actual);
        verifyNoInteractions(repoMemberMapper);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void shouldUseCachedRoleForPrivateRepositoryRead() {
        Repository repository = repository(true);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(valueOperations.get(CACHE_KEY)).thenReturn("COLLABORATOR");

        assertSame(repository, service.requireReadAccess(REPO_ID, ACTOR_ID));

        verify(repoMemberMapper, never()).findRole(REPO_ID, ACTOR_ID);
    }

    @Test
    void shouldProjectMySqlRoleIntoRedisAfterCacheMiss() {
        Repository repository = repository(true);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(repoMemberMapper.findRole(REPO_ID, ACTOR_ID))
                .thenReturn("collaborator");

        assertSame(repository, service.requireReadAccess(REPO_ID, ACTOR_ID));

        verify(valueOperations).set(
                CACHE_KEY,
                "COLLABORATOR",
                Duration.ofMinutes(5)
        );
    }

    @Test
    void shouldUseShortNegativeCacheForNonMember() {
        Repository repository = repository(true);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(repoMemberMapper.findRole(REPO_ID, ACTOR_ID)).thenReturn(null);

        RepositoryAccessService.AccessException exception = assertThrows(
                RepositoryAccessService.AccessException.class,
                () -> service.requireReadAccess(REPO_ID, ACTOR_ID)
        );

        assertEquals(
                RepositoryAccessService.AccessException.Reason.FORBIDDEN,
                exception.reason()
        );
        verify(valueOperations).set(CACHE_KEY, "NONE", Duration.ofSeconds(30));
    }

    @Test
    void shouldFallBackToMySqlWhenRedisReadFails() {
        Repository repository = repository(true);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(valueOperations.get(CACHE_KEY)).thenThrow(
                new RedisConnectionFailureException("redis unavailable")
        );
        when(repoMemberMapper.findRole(REPO_ID, ACTOR_ID)).thenReturn("owner");

        assertSame(repository, service.requireReadAccess(REPO_ID, ACTOR_ID));

        verify(repoMemberMapper).findRole(REPO_ID, ACTOR_ID);
    }

    @Test
    void shouldKeepMySqlResultWhenRedisWriteFails() {
        Repository repository = repository(true);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(repoMemberMapper.findRole(REPO_ID, ACTOR_ID)).thenReturn("owner");
        org.mockito.Mockito.doThrow(
                new RedisConnectionFailureException("redis unavailable")
        ).when(valueOperations).set(
                CACHE_KEY,
                "OWNER",
                Duration.ofMinutes(5)
        );

        assertSame(repository, service.requireReadAccess(REPO_ID, ACTOR_ID));
    }

    @Test
    void shouldTreatInvalidCacheValueAsMiss() {
        Repository repository = repository(true);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(valueOperations.get(CACHE_KEY)).thenReturn("BROKEN_ROLE");
        when(repoMemberMapper.findRole(REPO_ID, ACTOR_ID)).thenReturn("owner");

        assertSame(repository, service.requireReadAccess(REPO_ID, ACTOR_ID));

        verify(redisTemplate).delete(CACHE_KEY);
        verify(repoMemberMapper).findRole(REPO_ID, ACTOR_ID);
    }

    @Test
    void shouldNeverAuthorizeWriteFromStaleCachedOwnerRole() {
        Repository repository = repository(true);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(valueOperations.get(CACHE_KEY)).thenReturn("OWNER");
        when(repoMemberMapper.findRole(REPO_ID, ACTOR_ID)).thenReturn(null);

        RepositoryAccessService.AccessException exception = assertThrows(
                RepositoryAccessService.AccessException.class,
                () -> service.requireWriteAccess(REPO_ID, ACTOR_ID)
        );

        assertEquals(
                RepositoryAccessService.AccessException.Reason.FORBIDDEN,
                exception.reason()
        );
        verify(valueOperations, never()).get(CACHE_KEY);
        verify(repoMemberMapper).findRole(REPO_ID, ACTOR_ID);
    }

    @Test
    void shouldAllowCollaboratorWriteButRequireOwnerForAdministration() {
        Repository repository = repository(true);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(repoMemberMapper.findRole(REPO_ID, ACTOR_ID))
                .thenReturn("collaborator");

        assertSame(repository, service.requireWriteAccess(REPO_ID, ACTOR_ID));
        assertThrows(
                RepositoryAccessService.AccessException.class,
                () -> service.requireOwnerAccess(REPO_ID, ACTOR_ID)
        );
    }

    @Test
    void shouldAllowOwnerAdministration() {
        Repository repository = repository(true);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(repoMemberMapper.findRole(REPO_ID, ACTOR_ID)).thenReturn("owner");

        assertSame(repository, service.requireOwnerAccess(REPO_ID, ACTOR_ID));
    }

    @Test
    void shouldTreatMissingPrivacyFlagAsPrivate() {
        Repository repository = repository(true);
        repository.setIsPrivate(null);
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(valueOperations.get(CACHE_KEY)).thenReturn("NONE");

        assertThrows(
                RepositoryAccessService.AccessException.class,
                () -> service.requireReadAccess(REPO_ID, ACTOR_ID)
        );
    }

    @Test
    void shouldEvictOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.evictMemberAfterCommit(REPO_ID, ACTOR_ID);

        verify(redisTemplate, never()).delete(CACHE_KEY);
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(redisTemplate).delete(CACHE_KEY);
    }

    @Test
    void shouldReportMissingRepositoryBeforeUsingCache() {
        when(repositoryMapper.selectById(REPO_ID)).thenReturn(null);

        RepositoryAccessService.AccessException exception = assertThrows(
                RepositoryAccessService.AccessException.class,
                () -> service.requireReadAccess(REPO_ID, ACTOR_ID)
        );

        assertEquals(
                RepositoryAccessService.AccessException.Reason.REPOSITORY_NOT_FOUND,
                exception.reason()
        );
        verifyNoInteractions(redisTemplate);
    }

    private static Repository repository(boolean privateRepository) {
        Repository repository = new Repository();
        repository.setId(REPO_ID);
        repository.setOwnerId(100L);
        repository.setIsPrivate(privateRepository ? 1 : 0);
        return repository;
    }
}
