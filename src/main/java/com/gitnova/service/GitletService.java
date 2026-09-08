package com.gitnova.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.PushRequest;
import com.gitnova.dto.TransferMetadata;
import com.gitnova.gitlet.Commit;
import com.gitnova.gitlet.GitletException;
import com.gitnova.gitlet.Repository;
import com.gitnova.gitlet.Utils;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectCodec;
import com.gitnova.gitobject.GitObjectHasher;
import com.gitnova.gitobject.GitObjectId;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Gitlet core facade plus the hosted-push adapter for committed local history.
 *
 * <p>Legacy Java-serialized Commit objects are never sent to the server. Push
 * deterministically translates the local single-parent history into GitNova's
 * canonical Commit format and re-hashes raw Blob bytes.</p>
 */
@Service
public class GitletService {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");
    private static final DateTimeFormatter LEGACY_TIMESTAMP = DateTimeFormatter.ofPattern(
            "EEE MMM d HH:mm:ss yyyy Z",
            Locale.ENGLISH
    );
    private static final int MAX_COMMIT_HISTORY = 100_000;
    private static final int MAX_ERROR_MESSAGE_CHARS = 300;

    private final String basePath;
    private final ObjectMapper objectMapper;
    private final GitObjectCodec gitObjectCodec;
    private final OkHttpClient httpClient;

    @Autowired
    public GitletService(
            ObjectMapper objectMapper,
            GitObjectCodec gitObjectCodec,
            @Value("${gitnova.repo.base-path:./gitnova-repos}") String basePath,
            @Value("${gitnova.transfer.client-timeout:60}") long timeoutSeconds
    ) {
        this(
                basePath,
                objectMapper,
                gitObjectCodec,
                new OkHttpClient.Builder()
                        .callTimeout(Duration.ofSeconds(timeoutSeconds))
                        .build()
        );
    }

    GitletService(
            String basePath,
            ObjectMapper objectMapper,
            GitObjectCodec gitObjectCodec,
            OkHttpClient httpClient
    ) {
        this.basePath = requireNonBlank(basePath, "basePath");
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
        this.gitObjectCodec = Objects.requireNonNull(
                gitObjectCodec,
                "gitObjectCodec must not be null"
        );
        this.httpClient = Objects.requireNonNull(
                httpClient,
                "httpClient must not be null"
        );
    }

    private String resolvePath(String repoPath) {
        Path root = Path.of(basePath).toAbsolutePath().normalize();
        Path resolved = root.resolve(requireNonBlank(repoPath, "repoPath")).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("repoPath escapes the configured repository root");
        }
        return resolved.toString();
    }

    public void init(String repoPath) {
        new Repository(resolvePath(repoPath)).init();
    }

    public String commit(String repoPath, String message) {
        return new Repository(resolvePath(repoPath)).commit(message);
    }

    public Repository getRepository(String repoPath) {
        return new Repository(resolvePath(repoPath));
    }

    public String getHeadSha1(String repoPath) {
        return new Repository(resolvePath(repoPath)).getHeadSha1();
    }

    public boolean objectExists(String repoPath, String sha1) {
        Repository repository = new Repository(resolvePath(repoPath));
        return repository.blobExists(sha1) || repository.commitExists(sha1);
    }

    /**
     * Pushes the current committed local history through GitNova's hosted
     * negotiate/transfer protocol. Uncommitted working-tree changes are ignored.
     */
    public PushResult push(
            String repoPath,
            String remoteBaseUrl,
            long remoteRepoId,
            String branchName,
            String bearerToken
    ) {
        if (remoteRepoId <= 0) {
            throw new IllegalArgumentException("remoteRepoId must be positive");
        }
        String remoteBranch = BranchName.requireValid(
                branchName == null ? "main" : branchName
        );
        String token = requireNonBlank(bearerToken, "bearerToken");
        HttpUrl repositoryEndpoint = repositoryEndpoint(remoteBaseUrl, remoteRepoId);
        PreparedPush prepared = preparePush(new Repository(resolvePath(repoPath)));
        Negotiation negotiation = negotiate(
                repositoryEndpoint,
                remoteBranch,
                token,
                prepared
        );

        if (prepared.target().value().equals(negotiation.remoteHead())) {
            return new PushResult(prepared.target().value(), 0, true);
        }
        if (negotiation.remoteHead() != null
                && !prepared.commitIds().contains(GitObjectId.of(negotiation.remoteHead()))) {
            throw new GitletException(
                    "Push rejected: remote branch does not belong to the local commit history."
            );
        }

        transfer(
                repositoryEndpoint,
                remoteBranch,
                token,
                negotiation.remoteHead(),
                prepared,
                new ObjectPackRequestBody(
                        negotiation.missingObjects(),
                        prepared.objects()
                )
        );
        return new PushResult(
                prepared.target().value(),
                negotiation.missingObjects().size(),
                false
        );
    }

    public String getDiff(String repoPath, String commitSha1) {
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }

    private PreparedPush preparePush(Repository repository) {
        List<Commit> history = readHistory(repository);
        Map<GitObjectId, byte[]> objects = new LinkedHashMap<>();
        Set<GitObjectId> commitIds = new HashSet<>();
        Optional<GitObjectId> canonicalParent = Optional.empty();

        for (Commit legacy : history) {
            Map<String, GitObjectId> mapping = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : legacy.getMapping().entrySet()) {
                byte[] content = repository.readBlob(entry.getValue());
                requireLegacyBlobDigest(entry.getValue(), content);
                GitObjectId blobId = GitObjectHasher.sha1(content);
                mapping.put(entry.getKey(), blobId);
                objects.putIfAbsent(blobId, content);
            }

            CommitObject canonical = new CommitObject(
                    canonicalParent,
                    parseLegacyTimestamp(legacy.getTimestamp()),
                    legacy.getMessage(),
                    mapping
            );
            byte[] canonicalBytes = gitObjectCodec.encodeCommit(canonical);
            GitObjectId canonicalId = GitObjectHasher.sha1(canonicalBytes);
            objects.put(canonicalId, canonicalBytes);
            commitIds.add(canonicalId);
            canonicalParent = Optional.of(canonicalId);
        }

        GitObjectId target = canonicalParent.orElseThrow(
                () -> new GitletException("Local repository has no HEAD commit.")
        );
        return new PreparedPush(
                target,
                Map.copyOf(objects),
                Set.copyOf(commitIds),
                history.get(history.size() - 1).getMessage()
        );
    }

    private List<Commit> readHistory(Repository repository) {
        List<Commit> newestFirst = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = repository.getHeadSha1();
        while (current != null) {
            if (newestFirst.size() >= MAX_COMMIT_HISTORY) {
                throw new GitletException("Local commit history exceeds the supported limit.");
            }
            if (!seen.add(current)) {
                throw new GitletException("Local commit history contains a cycle.");
            }
            Commit commit = repository.readCommit(current);
            if (commit == null) {
                throw new GitletException("Local commit object is missing: " + current);
            }
            newestFirst.add(commit);
            current = commit.getParentCommit();
        }
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private Negotiation negotiate(
            HttpUrl repositoryEndpoint,
            String branchName,
            String token,
            PreparedPush prepared
    ) {
        PushRequest payload = new PushRequest();
        payload.setBranchName(branchName);
        payload.setLocalHeadSha1(prepared.target().value());
        payload.setLocalObjects(
                prepared.objects().keySet().stream()
                        .map(GitObjectId::value)
                        .toList()
        );
        JsonNode data = postJson(
                repositoryEndpoint.newBuilder()
                        .addPathSegment("push")
                        .addPathSegment("negotiate")
                        .build(),
                payload,
                token
        );

        String remoteHead = optionalObjectId(data.get("remoteHeadSha1"));
        JsonNode missingNode = data.get("missingObjects");
        if (missingNode == null || !missingNode.isArray()) {
            throw new GitletException("Remote negotiation response has no missingObjects array.");
        }
        List<GitObjectId> missing = new ArrayList<>();
        Set<GitObjectId> seen = new HashSet<>();
        for (JsonNode item : missingNode) {
            GitObjectId id = GitObjectId.of(item.asText());
            if (!prepared.objects().containsKey(id)) {
                throw new GitletException("Remote requested an unknown local object: " + id.value());
            }
            if (!seen.add(id)) {
                throw new GitletException("Remote requested the same object more than once.");
            }
            missing.add(id);
        }
        return new Negotiation(remoteHead, List.copyOf(missing));
    }

    private void transfer(
            HttpUrl repositoryEndpoint,
            String branchName,
            String token,
            String remoteHead,
            PreparedPush prepared,
            RequestBody objectPack
    ) {
        TransferMetadata metadata = new TransferMetadata();
        metadata.setBranchName(branchName);
        metadata.setBaseHeadSha1(remoteHead);
        metadata.setNewHeadSha1(prepared.target().value());
        metadata.setCommitMessage(prepared.message());
        metadata.setReview(false);

        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new GitletException("Could not encode push metadata.");
        }
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", metadataJson)
                .addFormDataPart(
                        "objects",
                        "objects.pack",
                        objectPack
                )
                .build();
        Request request = authorizedRequest(
                repositoryEndpoint.newBuilder()
                        .addPathSegment("push")
                        .addPathSegment("transfer")
                        .build(),
                token
        ).post(body).build();
        JsonNode data = execute(request);
        if (!prepared.target().value().equals(data.path("newHeadSha1").asText())) {
            throw new GitletException("Remote transfer response returned an unexpected HEAD.");
        }
    }

    private JsonNode postJson(HttpUrl endpoint, Object payload, String token) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new GitletException("Could not encode push request.");
        }
        Request request = authorizedRequest(endpoint, token)
                .post(RequestBody.create(json, JSON))
                .build();
        return execute(request);
    }

    private Request.Builder authorizedRequest(HttpUrl endpoint, String token) {
        return new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    private JsonNode execute(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String rawBody = body == null ? "" : body.string();
            JsonNode envelope;
            try {
                envelope = objectMapper.readTree(rawBody);
            } catch (JsonProcessingException exception) {
                throw new GitletException("Remote returned malformed JSON.");
            }
            if (envelope == null || !envelope.isObject()) {
                throw new GitletException("Remote returned malformed JSON.");
            }
            if (!response.isSuccessful() || envelope.path("code").asInt() != 200) {
                String message = envelope.path("message").asText("remote rejected the request");
                throw new GitletException("Push failed: " + truncate(message));
            }
            JsonNode data = envelope.get("data");
            if (data == null || data.isNull()) {
                throw new GitletException("Remote response has no data object.");
            }
            return data;
        } catch (GitletException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new GitletException("Push request failed: " + truncate(exception.getMessage()));
        }
    }

    private static HttpUrl repositoryEndpoint(String remoteBaseUrl, long remoteRepoId) {
        String value = requireNonBlank(remoteBaseUrl, "remoteBaseUrl");
        HttpUrl base = HttpUrl.parse(value);
        if (base == null) {
            throw new IllegalArgumentException("remoteBaseUrl is invalid");
        }
        return base.newBuilder()
                .addPathSegment("api")
                .addPathSegment("repos")
                .addPathSegment(Long.toString(remoteRepoId))
                .build();
    }

    private static void requireLegacyBlobDigest(String expected, byte[] content) {
        String actual = Utils.sha1(Utils.serialize(content));
        if (!actual.equals(expected)) {
            throw new GitletException("Local Blob content is corrupt: " + expected);
        }
    }

    private static Instant parseLegacyTimestamp(String value) {
        try {
            return ZonedDateTime.parse(value, LEGACY_TIMESTAMP).toInstant();
        } catch (DateTimeParseException exception) {
            throw new GitletException("Local Commit has an invalid timestamp.");
        }
    }

    private static String optionalObjectId(JsonNode value) {
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return GitObjectId.of(value.asText()).value();
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "remote request failed";
        }
        return value.length() <= MAX_ERROR_MESSAGE_CHARS
                ? value
                : value.substring(0, MAX_ERROR_MESSAGE_CHARS);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record PreparedPush(
            GitObjectId target,
            Map<GitObjectId, byte[]> objects,
            Set<GitObjectId> commitIds,
            String message
    ) {
    }

    private record Negotiation(String remoteHead, List<GitObjectId> missingObjects) {
    }

    /** Writes the negotiated pack directly to OkHttp's request sink. */
    private static final class ObjectPackRequestBody extends RequestBody {
        private static final int SHA1_BYTES = 40;

        private final List<GitObjectId> objectIds;
        private final Map<GitObjectId, byte[]> objects;
        private final long contentLength;

        private ObjectPackRequestBody(
                List<GitObjectId> objectIds,
                Map<GitObjectId, byte[]> objects
        ) {
            this.objectIds = List.copyOf(objectIds);
            this.objects = Map.copyOf(objects);
            long length = Integer.BYTES;
            for (GitObjectId id : this.objectIds) {
                byte[] content = this.objects.get(id);
                if (content == null) {
                    throw new GitletException("Missing local object content: " + id.value());
                }
                length = Math.addExact(
                        length,
                        SHA1_BYTES + Long.BYTES + content.length
                );
            }
            this.contentLength = length;
        }

        @Override
        public MediaType contentType() {
            return OCTET_STREAM;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            sink.writeInt(objectIds.size());
            for (GitObjectId id : objectIds) {
                byte[] content = objects.get(id);
                sink.write(id.value().getBytes(StandardCharsets.US_ASCII));
                sink.writeLong(content.length);
                sink.write(content);
            }
        }
    }

    public record PushResult(
            String remoteHeadSha1,
            int objectsUploaded,
            boolean alreadyUpToDate
    ) {
    }
}
