package com.gitnova.gitobject;

public interface GitObjectCodec {

    byte[] encodeCommit(CommitObject commit);

    CommitObject decodeCommit(byte[] canonicalBytes);

    ObjectType detectType(byte[] canonicalBytes);
}
