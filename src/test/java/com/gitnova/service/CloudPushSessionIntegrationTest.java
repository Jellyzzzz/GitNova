package com.gitnova.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.gitlet.Repository;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectCodec;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.storage.ObjectStorage;
import com.gitnova.storage.RepoKey;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real local-cloud vertical slice: HTTP auth/repository APIs, hosted Push,
 * canonical ObjectStorage, branch CAS, durable Session and Workspace publish.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gitnova.rate-limit.enabled=true",
                "gitnova.rate-limit.user.capacity-permits=1000",
                "gitnova.rate-limit.repository.capacity-permits=1000",
                "gitnova.rate-limit.api.capacity-permits=1000",
                "gitnova.rate-limit.user.refill-permits-per-second=1000",
                "gitnova.rate-limit.repository.refill-permits-per-second=1000",
                "gitnova.rate-limit.api.refill-permits-per-second=1000",
                "gitnova.rate-limit.user.idle-ttl=5s",
                "gitnova.rate-limit.repository.idle-ttl=5s",
                "gitnova.rate-limit.api.idle-ttl=5s",
                "spring.rabbitmq.listener.simple.auto-startup=false"
        }
)
@Tag("cloud-e2e")
class CloudPushSessionIntegrationTest {

    private static final Path TEST_ROOT = createTestRoot();
    private static final String PASSWORD = "test-password";

    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.password",
                () -> System.getenv().getOrDefault("DB_PASSWORD", "123456")
        );
        registry.add(
                "gitnova.repo.base-path",
                () -> TEST_ROOT.resolve("server-objects").toString()
        );
        registry.add(
                "gitnova.workspace.base-path",
                () -> TEST_ROOT.resolve("workspaces").toString()
        );
        registry.add(
                "gitnova.agent.artifact.base-path",
                () -> TEST_ROOT.resolve("artifacts").toString()
        );
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    GitObjectCodec gitObjectCodec;

    @Autowired
    GitObjectReader gitObjectReader;

    @Autowired
    ObjectStorage objectStorage;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    private Long userId;
    private Long repoId;
    private String sessionId;

    @Test
    void shouldPushCommittedHistoryAndMaterializeAnIdempotentSession() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "e2e_" + suffix.substring(0, 12);
        userId = register(username);
        String token = login(username);
        repoId = createRepository(token, "repo-" + suffix.substring(0, 12));

        Path clientRoot = Files.createDirectories(TEST_ROOT.resolve("clients").resolve(suffix));
        GitletService client = new GitletService(
                clientRoot.toString(),
                objectMapper,
                gitObjectCodec,
                new OkHttpClient()
        );
        client.init("local");
        Repository local = client.getRepository("local");
        Path localRoot = local.getRepoRoot().toPath();

        byte[] committedReadme = "# GitNova cloud E2E\n".getBytes();
        byte[] firstApp = "package demo; public class App {}\n".getBytes();
        byte[] finalApp = "package demo; public class App { int version = 2; }\n".getBytes();
        byte[] config = "profile: test\n".getBytes();
        write(localRoot.resolve("README.md"), committedReadme);
        write(localRoot.resolve("src/main/java/demo/App.java"), firstApp);
        write(localRoot.resolve("config/application-test.yml"), config);
        local.add("README.md");
        local.add("src/main/java/demo/App.java");
        local.add("config/application-test.yml");
        client.commit("local", "initial hosted snapshot");

        GitletService.PushResult firstPush = client.push(
                "local",
                serverBaseUrl(),
                repoId,
                "main",
                token
        );
        assertFalse(firstPush.alreadyUpToDate());

        write(localRoot.resolve("src/main/java/demo/App.java"), finalApp);
        local.add("src/main/java/demo/App.java");
        client.commit("local", "advance hosted snapshot");
        write(localRoot.resolve("README.md"), "uncommitted local change\n".getBytes());

        GitletService.PushResult secondPush = client.push(
                "local",
                serverBaseUrl(),
                repoId,
                "main",
                token
        );
        assertFalse(secondPush.alreadyUpToDate());
        assertNotEquals(firstPush.remoteHeadSha1(), secondPush.remoteHeadSha1());

        GitletService.PushResult pushRetry = client.push(
                "local",
                serverBaseUrl(),
                repoId,
                "main",
                token
        );
        assertTrue(pushRetry.alreadyUpToDate());
        assertEquals(secondPush.remoteHeadSha1(), pushRetry.remoteHeadSha1());
        assertEquals(0, pushRetry.objectsUploaded());

        RepoKey repoKey = RepoKey.of(userId, repoId);
        assertEquals(secondPush.remoteHeadSha1(), branchHead());
        assertTrue(objectStorage.existsObject(repoKey.value(), secondPush.remoteHeadSha1()));
        CommitObject target = gitObjectReader.requireCommit(
                repoKey.value(),
                secondPush.remoteHeadSha1()
        );
        assertEquals(
                Set.of(
                        "README.md",
                        "src/main/java/demo/App.java",
                        "config/application-test.yml"
                ),
                target.mapping().keySet()
        );
        target.mapping().values().forEach(blob -> assertTrue(
                objectStorage.existsObject(repoKey.value(), blob.value())
        ));

        String idempotencyKey = "session-" + suffix;
        ResponseEntity<String> sessionResponse = createSession(token, idempotencyKey);
        JsonNode session = requireSuccess(sessionResponse);
        sessionId = session.path("sessionId").asText();
        String workspaceId = session.path("workspaceId").asText();
        assertEquals("ACTIVE", session.path("status").asText());
        assertEquals(secondPush.remoteHeadSha1(), session.path("baseRevision").asText());
        assertEquals(2L, session.path("lastSessionSequence").asLong());
        assertEquals("READY", workspaceStatus(workspaceId));
        assertEquals(2L, stepCount(sessionId));

        Path workspaceRoot = Path.of(workspaceProviderRef(workspaceId));
        assertArrayEquals(committedReadme, Files.readAllBytes(workspaceRoot.resolve("README.md")));
        assertArrayEquals(finalApp, Files.readAllBytes(
                workspaceRoot.resolve("src/main/java/demo/App.java")
        ));
        assertArrayEquals(config, Files.readAllBytes(
                workspaceRoot.resolve("config/application-test.yml")
        ));
        assertEquals(
                Set.of(
                        "README.md",
                        "config/application-test.yml",
                        "src/main/java/demo/App.java"
                ),
                workspaceFiles(workspaceRoot)
        );

        JsonNode retry = requireSuccess(createSession(token, idempotencyKey));
        assertEquals(sessionId, retry.path("sessionId").asText());
        assertEquals(workspaceId, retry.path("workspaceId").asText());
        assertEquals(1L, sessionCountForRepository());
        assertEquals(2L, stepCount(sessionId));
    }

    @AfterEach
    void cleanDatabaseAndRedis() {
        if (sessionId != null) {
            jdbcTemplate.update("DELETE FROM agent_step WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM agent_workspace WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM agent_session WHERE session_id = ?", sessionId);
        }
        if (repoId != null) {
            jdbcTemplate.update("DELETE FROM commit_record WHERE repo_id = ?", repoId);
            jdbcTemplate.update("DELETE FROM branch WHERE repo_id = ?", repoId);
            jdbcTemplate.update("DELETE FROM repo_member WHERE repo_id = ?", repoId);
            jdbcTemplate.update("DELETE FROM repository WHERE id = ?", repoId);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
        }
        if (repoId != null && userId != null) {
            redisTemplate.delete("gitnova:repo-access:" + repoId + ":" + userId);
            redisTemplate.delete("gitnova:rate:repo:" + repoId);
            redisTemplate.delete("gitnova:rate:user:" + userId);
        }
    }

    @AfterAll
    static void cleanFiles() throws IOException {
        FileSystemUtils.deleteRecursively(TEST_ROOT);
    }

    private long register(String username) throws Exception {
        ResponseEntity<String> response = postForm(
                "/api/auth/register",
                Map.of(
                        "username", username,
                        "password", PASSWORD,
                        "email", username + "@example.test"
                ),
                null
        );
        return requireSuccess(response).path("id").asLong();
    }

    private String login(String username) throws Exception {
        return requireSuccess(postForm(
                "/api/auth/login",
                Map.of("username", username, "password", PASSWORD),
                null
        )).asText();
    }

    private long createRepository(String token, String name) throws Exception {
        ResponseEntity<String> response = postForm(
                "/api/repos",
                Map.of(
                        "name", name,
                        "description", "cloud E2E repository",
                        "isPrivate", "true"
                ),
                token
        );
        assertTrue(response.getHeaders().containsKey("X-RateLimit-Remaining"));
        return requireSuccess(response).path("id").asLong();
    }

    private ResponseEntity<String> createSession(String token, String idempotencyKey) {
        HttpHeaders headers = authorizedHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.postForEntity(
                serverBaseUrl() + "/api/repos/" + repoId + "/agent/sessions",
                new HttpEntity<>("{\"branchName\":\"main\"}", headers),
                String.class
        );
    }

    private ResponseEntity<String> postForm(
            String path,
            Map<String, String> values,
            String token
    ) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        values.forEach(form::add);
        HttpHeaders headers = token == null ? new HttpHeaders() : authorizedHeaders(token);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.postForEntity(
                serverBaseUrl() + path,
                new HttpEntity<>(form, headers),
                String.class
        );
    }

    private JsonNode requireSuccess(ResponseEntity<String> response) throws Exception {
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        JsonNode envelope = objectMapper.readTree(response.getBody());
        assertEquals(200, envelope.path("code").asInt(), response.getBody());
        return envelope.path("data");
    }

    private HttpHeaders authorizedHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private String branchHead() {
        return jdbcTemplate.queryForObject(
                "SELECT head_commit FROM branch WHERE repo_id = ? AND name = 'main'",
                String.class,
                repoId
        );
    }

    private String workspaceStatus(String workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM agent_workspace WHERE workspace_id = ?",
                String.class,
                workspaceId
        );
    }

    private String workspaceProviderRef(String workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_ref FROM agent_workspace WHERE workspace_id = ?",
                String.class,
                workspaceId
        );
    }

    private long stepCount(String persistedSessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_step WHERE session_id = ?",
                Long.class,
                persistedSessionId
        );
    }

    private long sessionCountForRepository() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_session WHERE repo_id = ?",
                Long.class,
                repoId
        );
    }

    private static Set<String> workspaceFiles(Path workspaceRoot) throws IOException {
        try (var paths = Files.walk(workspaceRoot)) {
            return paths.filter(Files::isRegularFile)
                    .map(workspaceRoot::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static void write(Path path, byte[] content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    private static Path createTestRoot() {
        try {
            return Files.createTempDirectory("gitnova-cloud-e2e-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
