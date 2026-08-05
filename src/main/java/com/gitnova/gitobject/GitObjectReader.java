package com.gitnova.gitobject;

import com.gitnova.gitlet.Commit;

public interface GitObjectReader {
    Commit requireCommit(String repoKey,String sha1);

    byte[] requireBlob(String repoKey,String sha1);
}
