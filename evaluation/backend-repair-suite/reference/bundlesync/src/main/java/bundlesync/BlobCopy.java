package bundlesync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class BlobCopy {
    public void copy(InputStream input, OutputStream output, long expectedLength, String expectedDigest) throws IOException {
        if (expectedLength < 0) throw new IllegalArgumentException("negative length");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        byte[] buffer = new byte[4096];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            total += count;
            if (total > expectedLength) throw new IOException("blob exceeds declared length");
            digest.update(buffer, 0, count);
            output.write(buffer, 0, count);
        }
        if (total != expectedLength || !HexFormat.of().formatHex(digest.digest()).equals(expectedDigest)) {
            throw new IOException("blob integrity mismatch");
        }
    }
}
