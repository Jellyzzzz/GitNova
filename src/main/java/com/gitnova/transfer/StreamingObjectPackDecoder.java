package com.gitnova.transfer;

import com.gitnova.gitobject.GitObjectId;
import com.gitnova.storage.config.RepositoryStorageProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict streaming decoder for [count][40-byte SHA][8-byte length][content] Pack bodies. */
@Component
public final class StreamingObjectPackDecoder implements ObjectPackDecoder {

    private final Path stagingRoot;

    public StreamingObjectPackDecoder(RepositoryStorageProperties repositoryStorageProperties) {
        this.stagingRoot = repositoryStorageProperties.basePath()
                .toAbsolutePath()
                .normalize()
                .resolve(".transfer-staging")
                .normalize();
    }

    @Override
    public ValidatedPack decode(InputStream input, long declaredPackSize, TransferProperties limits) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (declaredPackSize < 0 || declaredPackSize > limits.maxPackSize().toBytes()) {
            throw new PackFormatException(PackFormatException.Reason.LIMIT_EXCEEDED,
                    "declared Pack size exceeds limit");
        }
        Path stagingDirectory = createStagingDirectory();
        List<ValidatedObject> objects = new ArrayList<>();
        try {
            CountingInput inputCounter = new CountingInput(input, limits.maxPackSize().toBytes());
            int objectCount = ByteBuffer.wrap(inputCounter.readExactly(Integer.BYTES, "object count")).getInt();
            if (objectCount < 0 || objectCount > limits.maxObjectsPerPush()) {
                throw new PackFormatException(PackFormatException.Reason.LIMIT_EXCEEDED,
                        "object count exceeds limit");
            }
            Set<GitObjectId> seen = new HashSet<>();
            long cumulativeObjectBytes = 0;
            for (int index = 0; index < objectCount; index++) {
                GitObjectId id = parseObjectId(inputCounter.readExactly(40, "object SHA-1"));
                if (!seen.add(id)) {
                    throw new PackFormatException(PackFormatException.Reason.MALFORMED,
                            "Pack contains the same object more than once");
                }
                long length = ByteBuffer.wrap(inputCounter.readExactly(Long.BYTES, "object length")).getLong();
                if (length < 0 || length > limits.maxObjectSize().toBytes()) {
                    throw new PackFormatException(PackFormatException.Reason.LIMIT_EXCEEDED,
                            "object length exceeds limit");
                }
                try {
                    cumulativeObjectBytes = Math.addExact(cumulativeObjectBytes, length);
                } catch (ArithmeticException exception) {
                    throw new PackFormatException(PackFormatException.Reason.LIMIT_EXCEEDED,
                            "Pack object lengths overflow", exception);
                }
                if (cumulativeObjectBytes > limits.maxPackSize().toBytes()) {
                    throw new PackFormatException(PackFormatException.Reason.LIMIT_EXCEEDED,
                            "Pack object lengths exceed limit");
                }
                Path temporaryFile = stagingDirectory.resolve(String.format("%06d.object", index));
                copyVerifiedObject(inputCounter, temporaryFile, length, id, (int) limits.ioBufferSize().toBytes());
                objects.add(new ValidatedObject(id, temporaryFile, length));
            }
            if (inputCounter.readOne() != -1) {
                throw new PackFormatException(PackFormatException.Reason.MALFORMED,
                        "Pack contains trailing bytes");
            }
            if (inputCounter.count() != declaredPackSize) {
                throw new PackFormatException(PackFormatException.Reason.MALFORMED,
                        "declared Pack size does not match body size");
            }
            return new ValidatedPack(stagingDirectory, objects);
        } catch (RuntimeException exception) {
            new ValidatedPack(stagingDirectory, objects).close();
            throw exception;
        }
    }

    private Path createStagingDirectory() {
        try {
            Files.createDirectories(stagingRoot);
            if (Files.isSymbolicLink(stagingRoot)) {
                throw new PackFormatException(PackFormatException.Reason.MALFORMED,
                        "transfer staging root must not be a symbolic link");
            }
            return Files.createTempDirectory(stagingRoot, "pack-");
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create controlled Pack staging directory", exception);
        }
    }

    private static GitObjectId parseObjectId(byte[] bytes) {
        return GitObjectId.of(new String(bytes, StandardCharsets.US_ASCII));
    }

    private static void copyVerifiedObject(CountingInput input, Path target, long length,
                                           GitObjectId expectedId, int bufferSize) {
        MessageDigest digest = sha1Digest();
        try (FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[bufferSize];
            long remaining = length;
            while (remaining > 0) {
                int requested = (int) Math.min(buffer.length, remaining);
                input.readExactlyInto(buffer, requested, "object content");
                digest.update(buffer, 0, requested);
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, requested);
                while (byteBuffer.hasRemaining()) {
                    output.write(byteBuffer);
                }
                remaining -= requested;
            }
            output.force(true);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write Pack object staging file", exception);
        }
        if (!toObjectId(digest.digest()).equals(expectedId)) {
            throw new PackFormatException(PackFormatException.Reason.DIGEST_MISMATCH,
                    "object content digest does not match declared SHA-1");
        }
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required by the Java runtime", exception);
        }
    }

    private static GitObjectId toObjectId(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return GitObjectId.of(hex.toString());
    }

    private static final class CountingInput {
        private final InputStream delegate;
        private final long maximum;
        private long count;

        private CountingInput(InputStream delegate, long maximum) {
            this.delegate = delegate;
            this.maximum = maximum;
        }

        private byte[] readExactly(int length, String field) {
            byte[] result = new byte[length];
            readExactlyInto(result, length, field);
            return result;
        }

        private void readExactlyInto(byte[] destination, int length, String field) {
            int offset = 0;
            try {
                while (offset < length) {
                    int read = delegate.read(destination, offset, length - offset);
                    if (read < 0) {
                        throw new PackFormatException(PackFormatException.Reason.MALFORMED,
                                "truncated " + field);
                    }
                    if (read == 0) {
                        throw new IllegalStateException("Pack input made no forward progress");
                    }
                    increase(read);
                    offset += read;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("failed to read Pack", exception);
            }
        }

        private int readOne() {
            try {
                int value = delegate.read();
                if (value >= 0) {
                    increase(1);
                }
                return value;
            } catch (IOException exception) {
                throw new IllegalStateException("failed to read Pack", exception);
            }
        }

        private long count() {
            return count;
        }

        private void increase(long amount) {
            try {
                count = Math.addExact(count, amount);
            } catch (ArithmeticException exception) {
                throw new PackFormatException(PackFormatException.Reason.LIMIT_EXCEEDED,
                        "Pack size overflows", exception);
            }
            if (count > maximum) {
                throw new PackFormatException(PackFormatException.Reason.LIMIT_EXCEEDED,
                        "Pack size exceeds limit");
            }
        }
    }
}
