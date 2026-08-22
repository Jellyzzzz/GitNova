package com.gitnova.gitobject;

import com.gitnova.storage.FakeObjectStorage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Canonical object fixtures shared by hosted-object and Agent tool tests. */
public final class GitObjectTestFixtures {

    private static final GitObjectCodec CODEC = new CanonicalGitObjectCodec();
    private static final Instant FIXED_TIMESTAMP = Instant.parse("2026-08-20T00:00:00Z");

    private GitObjectTestFixtures() {
    }

    public static String objectId(char hexadecimalDigit) {
        if (Character.digit(hexadecimalDigit, 16) < 0) {
            throw new IllegalArgumentException("object fixture digit must be hexadecimal");
        }
        return String.valueOf(Character.toLowerCase(hexadecimalDigit)).repeat(40);
    }

    public static GitObjectReader reader(FakeObjectStorage storage) {
        return new ObjectStorageGitObjectReader(storage, CODEC);
    }

    public static CommitObject commit(
            String parentSha1,
            String message,
            Map<String, String> mapping
    ) {
        Map<String, GitObjectId> typedMapping = new LinkedHashMap<>();
        mapping.forEach((path, sha1) -> typedMapping.put(path, GitObjectId.of(sha1)));
        return new CommitObject(
                parentSha1 == null
                        ? Optional.empty()
                        : Optional.of(GitObjectId.of(parentSha1)),
                FIXED_TIMESTAMP,
                message,
                typedMapping
        );
    }

    public static void writeCommit(
            FakeObjectStorage storage,
            String repoKey,
            String sha1,
            String parentSha1,
            String message,
            Map<String, String> mapping
    ) {
        storage.writeObject(
                repoKey,
                sha1,
                CODEC.encodeCommit(commit(parentSha1, message, mapping))
        );
    }

    public static byte[] encodeCommit(CommitObject commit) {
        return CODEC.encodeCommit(commit);
    }
}
