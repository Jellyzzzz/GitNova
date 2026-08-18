package com.gitnova.gitobject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Derives a content address from the exact bytes stored for an object. */
public final class GitObjectHasher {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private GitObjectHasher() {
    }

    public static GitObjectId sha1(byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(content);
            char[] result = new char[digest.length * 2];
            for (int index = 0; index < digest.length; index++) {
                int value = Byte.toUnsignedInt(digest[index]);
                result[index * 2] = HEX[value >>> 4];
                result[index * 2 + 1] = HEX[value & 0x0f];
            }
            return GitObjectId.of(new String(result));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required by the Java runtime", exception);
        }
    }
}
