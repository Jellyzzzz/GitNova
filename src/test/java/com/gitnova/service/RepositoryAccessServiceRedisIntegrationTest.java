package com.gitnova.service;

import com.gitnova.entity.Repository;
import com.gitnova.mapper.RepoMemberMapper;
import com.gitnova.mapper.RepositoryMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("redis-it")
class RepositoryAccessServiceRedisIntegrationTest {

    private static final long ACTOR_ID = 7L;
    private static final long REPO_ID = positiveRandomId();
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = connectionFactory();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedis() {
        if (redisTemplate != null) {
            redisTemplate.delete(cacheKey());
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldShareAndInvalidateRoleProjectionAcrossServiceInstances() {
        Repository repository = repository();
        RepositoryMapper firstRepositoryMapper = mock(RepositoryMapper.class);
        RepositoryMapper secondRepositoryMapper = mock(RepositoryMapper.class);
        RepoMemberMapper firstMemberMapper = mock(RepoMemberMapper.class);
        RepoMemberMapper secondMemberMapper = mock(RepoMemberMapper.class);
        when(firstRepositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(secondRepositoryMapper.selectById(REPO_ID)).thenReturn(repository);
        when(firstMemberMapper.findRole(REPO_ID, ACTOR_ID))
                .thenReturn("collaborator");
        when(secondMemberMapper.findRole(REPO_ID, ACTOR_ID)).thenReturn(null);
        RepositoryAccessService first = new RepositoryAccessService(
                firstRepositoryMapper,
                firstMemberMapper,
                redisTemplate
        );
        RepositoryAccessService second = new RepositoryAccessService(
                secondRepositoryMapper,
                secondMemberMapper,
                redisTemplate
        );
        redisTemplate.delete(cacheKey());

        assertSame(repository, first.requireReadAccess(REPO_ID, ACTOR_ID));
        assertSame(repository, second.requireReadAccess(REPO_ID, ACTOR_ID));
        verify(secondMemberMapper, never()).findRole(REPO_ID, ACTOR_ID);

        first.evictMemberAfterCommit(REPO_ID, ACTOR_ID);

        assertThrows(
                RepositoryAccessService.AccessException.class,
                () -> second.requireReadAccess(REPO_ID, ACTOR_ID)
        );
        verify(secondMemberMapper).findRole(REPO_ID, ACTOR_ID);
    }

    private static LettuceConnectionFactory connectionFactory() {
        String host = System.getProperty(
                "redis.host",
                System.getenv().getOrDefault("REDIS_HOST", "localhost")
        );
        int port = Integer.parseInt(System.getProperty(
                "redis.port",
                System.getenv().getOrDefault("REDIS_PORT", "6379")
        ));
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(host, port);
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            configuration.setPassword(password);
        }
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        return connectionFactory;
    }

    private static Repository repository() {
        Repository repository = new Repository();
        repository.setId(REPO_ID);
        repository.setOwnerId(100L);
        repository.setIsPrivate(1);
        return repository;
    }

    private static String cacheKey() {
        return "gitnova:repo-access:" + REPO_ID + ":" + ACTOR_ID;
    }

    private static long positiveRandomId() {
        return Math.max(
                1L,
                UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE
        );
    }
}
