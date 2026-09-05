package com.gitnova.service;

import com.gitnova.entity.Repository;
import com.gitnova.mapper.RepoMemberMapper;
import com.gitnova.mapper.RepositoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Central repository authorization boundary with a Redis Cache Aside read path. */
@Service
public final class RepositoryAccessService {

    private static final Duration MEMBER_TTL = Duration.ofMinutes(5);
    private static final Duration NON_MEMBER_TTL = Duration.ofSeconds(30);
    private static final String CACHE_KEY_PREFIX = "gitnova:repo-access:";
    private static final Logger logger = LoggerFactory.getLogger(
            RepositoryAccessService.class
    );

    private final RepositoryMapper repositoryMapper;
    private final RepoMemberMapper repoMemberMapper;
    private final StringRedisTemplate redisTemplate;

    public RepositoryAccessService(
            RepositoryMapper repositoryMapper,
            RepoMemberMapper repoMemberMapper,
            StringRedisTemplate redisTemplate
    ) {
        this.repositoryMapper = Objects.requireNonNull(
                repositoryMapper,
                "repositoryMapper must not be null"
        );
        this.repoMemberMapper = Objects.requireNonNull(
                repoMemberMapper,
                "repoMemberMapper must not be null"
        );
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate,
                "redisTemplate must not be null"
        );
    }

    /** Public repositories are readable by every authenticated user. */
    public Repository requireReadAccess(Long repoId, long actorId) {
        requireActorId(actorId);
        Repository repository = requireRepository(repoId);
        if (!isPrivate(repository)) {
            return repository;
        }
        if (findRoleCached(repoId, actorId) == MemberRole.NONE) {
            throw AccessException.forbidden("无权访问该仓库");
        }
        return repository;
    }

    /** Mutating entry points always authorize against MySQL, never a cached role. */
    public Repository requireWriteAccess(Long repoId, long actorId) {
        requireActorId(actorId);
        Repository repository = requireRepository(repoId);
        MemberRole role = loadRoleFromMySql(repoId, actorId);
        cacheBestEffort(repoId, actorId, role);
        if (role == MemberRole.NONE) {
            throw AccessException.forbidden("无权修改该仓库");
        }
        return repository;
    }

    /** Destructive repository administration is owner-only and MySQL-authorized. */
    public Repository requireOwnerAccess(Long repoId, long actorId) {
        requireActorId(actorId);
        Repository repository = requireRepository(repoId);
        MemberRole role = loadRoleFromMySql(repoId, actorId);
        cacheBestEffort(repoId, actorId, role);
        if (role != MemberRole.OWNER) {
            throw AccessException.forbidden("仅仓库所有者可执行该操作");
        }
        return repository;
    }

    /** Evicts one role projection after the surrounding MySQL transaction commits. */
    public void evictMemberAfterCommit(Long repoId, long actorId) {
        requireRepoId(repoId);
        requireActorId(actorId);
        Runnable eviction = () -> evictBestEffort(repoId, actorId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eviction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eviction.run();
                    }
                }
        );
    }

    private Repository requireRepository(Long repoId) {
        requireRepoId(repoId);
        Repository repository = repositoryMapper.selectById(repoId);
        if (repository == null) {
            throw AccessException.notFound("仓库不存在");
        }
        return repository;
    }

    private MemberRole findRoleCached(Long repoId, long actorId) {
        String key = cacheKey(repoId, actorId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                MemberRole role = MemberRole.fromCache(cached);
                if (role != null) {
                    return role;
                }
                logger.warn("Ignoring invalid repository access cache value: key={}", key);
                redisTemplate.delete(key);
            }
        } catch (DataAccessException exception) {
            logger.warn(
                    "Repository access cache read failed: key={}, error={}",
                    key,
                    exception.getMessage()
            );
        }

        MemberRole role = loadRoleFromMySql(repoId, actorId);
        cacheBestEffort(repoId, actorId, role);
        return role;
    }

    private MemberRole loadRoleFromMySql(Long repoId, long actorId) {
        return MemberRole.fromDatabase(repoMemberMapper.findRole(repoId, actorId));
    }

    private void cacheBestEffort(Long repoId, long actorId, MemberRole role) {
        String key = cacheKey(repoId, actorId);
        Duration ttl = role == MemberRole.NONE ? NON_MEMBER_TTL : MEMBER_TTL;
        try {
            redisTemplate.opsForValue().set(key, role.name(), ttl);
        } catch (DataAccessException exception) {
            logger.warn(
                    "Repository access cache write failed: key={}, error={}",
                    key,
                    exception.getMessage()
            );
        }
    }

    private void evictBestEffort(Long repoId, long actorId) {
        String key = cacheKey(repoId, actorId);
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException exception) {
            logger.warn(
                    "Repository access cache eviction failed: key={}, error={}",
                    key,
                    exception.getMessage()
            );
        }
    }

    private static boolean isPrivate(Repository repository) {
        return !Integer.valueOf(0).equals(repository.getIsPrivate());
    }

    private static String cacheKey(Long repoId, long actorId) {
        return CACHE_KEY_PREFIX + repoId + ":" + actorId;
    }

    private static void requireRepoId(Long repoId) {
        if (repoId == null || repoId <= 0) {
            throw new IllegalArgumentException("repoId must be positive");
        }
    }

    private static void requireActorId(long actorId) {
        if (actorId <= 0) {
            throw new IllegalArgumentException("actorId must be positive");
        }
    }

    private enum MemberRole {
        OWNER,
        COLLABORATOR,
        NONE;

        private static MemberRole fromDatabase(String value) {
            if (value == null) {
                return NONE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "owner" -> OWNER;
                case "collaborator" -> COLLABORATOR;
                default -> throw new IllegalStateException(
                        "Unsupported repository member role: " + value
                );
            };
        }

        private static MemberRole fromCache(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    public static final class AccessException extends RuntimeException {
        private final Reason reason;

        private AccessException(Reason reason, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }

        public Reason reason() {
            return reason;
        }

        private static AccessException notFound(String message) {
            return new AccessException(Reason.REPOSITORY_NOT_FOUND, message);
        }

        private static AccessException forbidden(String message) {
            return new AccessException(Reason.FORBIDDEN, message);
        }

        public enum Reason {
            REPOSITORY_NOT_FOUND,
            FORBIDDEN
        }
    }
}
