package com.gitnova.gitobject;

import java.io.IOException;
import java.io.OutputStream;

public interface GitObjectReader {
    CommitObject requireCommit(String repoKey, String sha1);

    byte[] requireBlob(String repoKey, String sha1);

    long copyBlobTo(
            String repoKey,
            String sha1,
            OutputStream destination
    ) throws IOException;
}
