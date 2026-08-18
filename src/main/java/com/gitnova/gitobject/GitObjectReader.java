package com.gitnova.gitobject;

public interface GitObjectReader {
    CommitObject requireCommit(String repoKey, String sha1);

    byte[] requireBlob(String repoKey,String sha1);
}
