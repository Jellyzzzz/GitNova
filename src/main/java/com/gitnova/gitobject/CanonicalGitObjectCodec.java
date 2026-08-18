package com.gitnova.gitobject;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Version 1 of GitNova's deterministic commit object format.
 *
 * <p>All variable-sized fields are UTF-8 byte length prefixed. SHA-1 values
 * use their already-canonical forty-byte lower-case ASCII representation.</p>
 */
@Component
public final class CanonicalGitObjectCodec implements GitObjectCodec {

    private static final byte[] MAGIC = {'G', 'N', 'O', 'V'};
    private static final byte FORMAT_VERSION = 1;
    private static final int SHA1_ASCII_LENGTH = 40;
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private static final int MAX_PATH_BYTES = 4 * 1024;
    private static final int MAX_MAPPING_ENTRIES = 100_000;
    private static final int MAX_CANONICAL_COMMIT_BYTES = 32 * 1024 * 1024;

    @Override
    public byte[] encodeCommit(CommitObject commit) {
        Objects.requireNonNull(commit, "commit must not be null");
        byte[] message = utf8(commit.message(), "message");
        requireLimit(message.length, MAX_MESSAGE_BYTES, "message exceeds canonical size limit");

        List<EncodedEntry> entries = new ArrayList<>(commit.mapping().size());
        for (Map.Entry<String, GitObjectId> entry : commit.mapping().entrySet()) {
            byte[] path = utf8(entry.getKey(), "mapping path");
            requireLimit(path.length, MAX_PATH_BYTES, "mapping path exceeds canonical size limit");
            entries.add(new EncodedEntry(entry.getKey(), path, entry.getValue()));
        }
        requireLimit(entries.size(), MAX_MAPPING_ENTRIES, "mapping entry count exceeds canonical size limit");
        entries.sort(Comparator.comparing(EncodedEntry::path));

        long size = 4L + 1 + 1 + 1 + 8 + 4 + 4 + message.length + 4;
        if (commit.parentSha1().isPresent()) {
            size += SHA1_ASCII_LENGTH;
        }
        for (EncodedEntry entry : entries) {
            size = checkedAdd(size, 4L + entry.utf8Path().length + SHA1_ASCII_LENGTH);
        }
        if (size > MAX_CANONICAL_COMMIT_BYTES) {
            throw new CommitCodecException(CommitCodecException.Reason.LIMIT_EXCEEDED,
                    "canonical commit exceeds size limit");
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
            try (DataOutputStream data = new DataOutputStream(output)) {
                data.write(MAGIC);
                data.writeByte(FORMAT_VERSION);
                data.writeByte(ObjectType.COMMIT.code());
                data.writeByte(commit.parentSha1().isPresent() ? 1 : 0);
                if (commit.parentSha1().isPresent()) {
                    writeObjectId(data, commit.parentSha1().orElseThrow());
                }
                data.writeLong(commit.timestamp().getEpochSecond());
                data.writeInt(commit.timestamp().getNano());
                data.writeInt(message.length);
                data.write(message);
                data.writeInt(entries.size());
                for (EncodedEntry entry : entries) {
                    data.writeInt(entry.utf8Path().length);
                    data.write(entry.utf8Path());
                    writeObjectId(data, entry.blobSha1());
                }
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encode canonical commit", exception);
        }
    }

    @Override
    public CommitObject decodeCommit(byte[] canonicalBytes) {
        Objects.requireNonNull(canonicalBytes, "canonicalBytes must not be null");
        if (canonicalBytes.length > MAX_CANONICAL_COMMIT_BYTES) {
            throw new CommitCodecException(CommitCodecException.Reason.LIMIT_EXCEEDED,
                    "canonical commit exceeds size limit");
        }
        ByteBuffer input = ByteBuffer.wrap(canonicalBytes).order(ByteOrder.BIG_ENDIAN);
        requireHeader(input, ObjectType.COMMIT);
        boolean hasParent = readParentPresence(input);
        Optional<GitObjectId> parent = hasParent ? Optional.of(readObjectId(input, "parentSha1")) : Optional.empty();
        long epochSecond = readLong(input, "timestamp epochSecond");
        int nanoAdjustment = readInt(input, "timestamp nanoAdjustment");
        if (nanoAdjustment < 0 || nanoAdjustment > 999_999_999) {
            throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                    "timestamp nanoAdjustment is out of range");
        }
        Instant timestamp;
        try {
            timestamp = Instant.ofEpochSecond(epochSecond, nanoAdjustment);
        } catch (DateTimeException exception) {
            throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                    "invalid timestamp", exception);
        }
        String message = readUtf8(input, readLength(input, MAX_MESSAGE_BYTES, "message"), "message");
        int entryCount = readLength(input, MAX_MAPPING_ENTRIES, "mapping entry count");
        Map<String, GitObjectId> mapping = new LinkedHashMap<>();
        String previousPath = null;
        for (int index = 0; index < entryCount; index++) {
            String path = readUtf8(input, readLength(input, MAX_PATH_BYTES, "mapping path"), "mapping path");
            if (previousPath != null && previousPath.compareTo(path) >= 0) {
                throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                        "mapping paths must be strictly sorted");
            }
            mapping.put(path, readObjectId(input, "blobSha1"));
            previousPath = path;
        }
        if (input.hasRemaining()) {
            throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                    "canonical commit contains trailing bytes");
        }
        try {
            return new CommitObject(parent, timestamp, message, mapping);
        } catch (IllegalArgumentException exception) {
            throw new CommitCodecException(CommitCodecException.Reason.INVARIANT,
                    "commit invariants are invalid", exception);
        }
    }

    @Override
    public ObjectType detectType(byte[] canonicalBytes) {
        Objects.requireNonNull(canonicalBytes, "canonicalBytes must not be null");
        ByteBuffer input = ByteBuffer.wrap(canonicalBytes).order(ByteOrder.BIG_ENDIAN);
        requireMagicAndVersion(input);
        try {
            return ObjectType.fromCode(readByte(input, "object type"));
        } catch (IllegalArgumentException exception) {
            throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                    "unsupported object type", exception);
        }
    }

    private static void requireHeader(ByteBuffer input, ObjectType expectedType) {
        requireMagicAndVersion(input);
        ObjectType actual;
        try {
            actual = ObjectType.fromCode(readByte(input, "object type"));
        } catch (IllegalArgumentException exception) {
            throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                    "unsupported object type", exception);
        }
        if (actual != expectedType) {
            throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                    "object type is not " + expectedType);
        }
    }

    private static void requireMagicAndVersion(ByteBuffer input) {
        requireRemaining(input, MAGIC.length + 1, "header");
        for (byte expected : MAGIC) {
            if (input.get() != expected) {
                throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                        "invalid canonical object magic");
            }
        }
        byte version = input.get();
        if (version != FORMAT_VERSION) {
            throw new CommitCodecException(CommitCodecException.Reason.UNSUPPORTED_VERSION,
                    "unsupported canonical object version: " + Byte.toUnsignedInt(version));
        }
    }

    private static boolean readParentPresence(ByteBuffer input) {
        byte value = readByte(input, "parent presence");
        if (value == 0) {
            return false;
        }
        if (value == 1) {
            return true;
        }
        throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                "parent presence must be 0 or 1");
    }

    private static GitObjectId readObjectId(ByteBuffer input, String field) {
        return GitObjectId.of(new String(readBytes(input, SHA1_ASCII_LENGTH, field), StandardCharsets.US_ASCII));
    }

    private static void writeObjectId(DataOutputStream output, GitObjectId objectId) throws IOException {
        byte[] value = objectId.value().getBytes(StandardCharsets.US_ASCII);
        if (value.length != SHA1_ASCII_LENGTH) {
            throw new IllegalStateException("GitObjectId must encode as forty ASCII bytes");
        }
        output.write(value);
    }

    private static String readUtf8(ByteBuffer input, int length, String field) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readBytes(input, length, field)))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                    field + " is not valid UTF-8", exception);
        }
    }

    private static byte[] utf8(String value, String field) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException exception) {
            throw new CommitCodecException(CommitCodecException.Reason.INVARIANT,
                    field + " is not valid UTF-8", exception);
        }
    }

    private static int readLength(ByteBuffer input, int maximum, String field) {
        int length = readInt(input, field + " length");
        if (length < 0) {
            throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                    field + " length must not be negative");
        }
        if (length > maximum) {
            throw new CommitCodecException(CommitCodecException.Reason.LIMIT_EXCEEDED,
                    field + " exceeds canonical size limit");
        }
        return length;
    }

    private static byte[] readBytes(ByteBuffer input, int length, String field) {
        requireRemaining(input, length, field);
        byte[] result = new byte[length];
        input.get(result);
        return result;
    }

    private static byte readByte(ByteBuffer input, String field) {
        requireRemaining(input, 1, field);
        return input.get();
    }

    private static int readInt(ByteBuffer input, String field) {
        requireRemaining(input, Integer.BYTES, field);
        return input.getInt();
    }

    private static long readLong(ByteBuffer input, String field) {
        requireRemaining(input, Long.BYTES, field);
        return input.getLong();
    }

    private static void requireRemaining(ByteBuffer input, int needed, String field) {
        if (needed < 0 || input.remaining() < needed) {
            throw new CommitCodecException(CommitCodecException.Reason.MALFORMED,
                    "truncated " + field);
        }
    }

    private static void requireLimit(int actual, int maximum, String message) {
        if (actual > maximum) {
            throw new CommitCodecException(CommitCodecException.Reason.LIMIT_EXCEEDED, message);
        }
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new CommitCodecException(CommitCodecException.Reason.LIMIT_EXCEEDED,
                    "canonical commit size overflows", exception);
        }
    }

    private record EncodedEntry(String path, byte[] utf8Path, GitObjectId blobSha1) {
    }
}
