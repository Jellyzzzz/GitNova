package com.gitnova.gitobject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record CommitObject(Optional<GitObjectId> parentSha1,
                           Instant timestamp,
                           String message,
                           Map<String, GitObjectId> mapping) {
    public CommitObject {
        parentSha1 = Objects.requireNonNull(parentSha1, "parentSha1 must not be null");
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        message = Objects.requireNonNull(message, "message must not be null");
        mapping = Objects.requireNonNull(mapping, "mapping must not be null");

        validateWellFormatedUtf16(message);
        Map<String, GitObjectId> copied = new LinkedHashMap<>();
        for (Map.Entry<String, GitObjectId> entry : mapping.entrySet()) {
            String path = Objects.requireNonNull(entry.getKey(), "mapping path must not be null");
            GitObjectId blobSha1 = Objects.requireNonNull(entry.getValue(), "mapping blobSha1 must not be null");
            validateRepositoryPath(path);
            validateWellFormatedUtf16(path);
            copied.put(path, blobSha1);
        }
        mapping = Map.copyOf(copied);
    }

    private static void validateWellFormatedUtf16(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("text contains an unpaired high surrogate at index " + i);
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("text contains an unpaired low surrogate at index " + i);
            }
        }
    }

    private static void validateRepositoryPath(String path) {
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (path.indexOf('\0') >= 0 || path.startsWith("/") || path.contains("\\")
                || path.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("path must be a repository-relative POSIX path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("path must be normalized");
            }
        }
    }
}
