package com.gitnova.gitobject;

import com.gitnova.storage.ObjectStorage;
import com.gitnova.storage.ObjectStorageException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Reads canonical Git objects from storage without an exists-then-read race. */
@Service
public class ObjectStorageGitObjectReader implements GitObjectReader {

    private static final int STREAM_BUFFER_BYTES = 64 * 1024;

    private final ObjectStorage objectStorage;
    private final GitObjectCodec gitObjectCodec;

    public ObjectStorageGitObjectReader(ObjectStorage objectStorage, GitObjectCodec gitObjectCodec) {
        this.objectStorage = objectStorage;
        this.gitObjectCodec = gitObjectCodec;
    }

    @Override
    public CommitObject requireCommit(String repoKey, String sha1) {
        byte[] commit = requireRawObject(repoKey, sha1);
        try {
            return gitObjectCodec.decodeCommit(commit);
        } catch (CommitCodecException exception) {
            throw new GitObjectReadException(
                    GitObjectReadException.Reason.CORRUPT,
                    "Object is not a valid canonical commit",
                    exception
            );
        }
    }

    @Override
    public byte[] requireBlob(String repoKey, String sha1) {
        return requireRawObject(repoKey, sha1);
    }

    @Override
    public long copyBlobTo(String repoKey, String sha1, OutputStream destination) throws IOException {
        Objects.requireNonNull(destination,"destination must not be null");
        GitObjectId expectedId=requireObjectId(repoKey,sha1);
        MessageDigest digest=sha1Digest();
        long totalBytes=0L;
        try(InputStream input=objectStorage.openObject(repoKey, expectedId.value())){
            byte[] buffer=new byte[STREAM_BUFFER_BYTES];
            while(true){
                int count;
                try{
                    count=input.read(buffer);
                }catch (IOException e){
                    throw new GitObjectReadException(GitObjectReadException.Reason.TRANSIENT,
                            "Failed to stream Git object",e);
                }
                if(count==-1) break;
                digest.update(buffer,0,count);
                destination.write(buffer,0,count);
                totalBytes+=count;
            }
        }catch (ObjectStorageException e){
            throw mapStorageFailure(e,expectedId);
        }
        GitObjectId actualId=objectIdFromDigest(digest.digest());
        if(!actualId.equals(expectedId)){
            throw new GitObjectReadException(
                    GitObjectReadException.Reason.CORRUPT,
                    "Streamed object digest does not match its address"
            );
        }
        return totalBytes;
    }

    private byte[] requireRawObject(String repoKey, String sha1) {
        GitObjectId id = requireObjectId(repoKey,sha1);
        try {
            return objectStorage.readObject(repoKey, id.value());
        } catch (ObjectStorageException exception) {
            throw mapStorageFailure(exception,id);
        } catch (RuntimeException exception) {
            throw new GitObjectReadException(
                    GitObjectReadException.Reason.TRANSIENT,
                    "Failed to read object", exception
            );
        }
    }
    private GitObjectId requireObjectId(
            String repoKey,
            String sha1
    ) {
        if (repoKey == null || repoKey.isBlank()) {
            throw new IllegalArgumentException(
                    "repoKey must not be blank"
            );
        }

        if (sha1 == null || sha1.isBlank()) {
            throw new IllegalArgumentException(
                    "sha1 must not be blank"
            );
        }

        return GitObjectId.of(sha1);
    }
    private MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-1 is required by the Java runtime",
                    exception
            );
        }
    }
    private GitObjectId objectIdFromDigest(
            byte[] digest
    ) {
        StringBuilder hex =
                new StringBuilder(digest.length * 2);

        for (byte value : digest) {
            hex.append(
                    Character.forDigit(
                            (value >>> 4) & 0x0f,
                            16
                    )
            );
            hex.append(
                    Character.forDigit(
                            value & 0x0f,
                            16
                    )
            );
        }
        return GitObjectId.of(hex.toString());
    }
    private GitObjectReadException mapStorageFailure(
            ObjectStorageException exception,GitObjectId id
    ) {
        return switch (exception.reason()) {
            case NOT_FOUND -> new GitObjectReadException(GitObjectReadException.Reason.NOT_FOUND,
                    "Object not found: " + id.value(), exception);
            case CORRUPT -> new GitObjectReadException(GitObjectReadException.Reason.CORRUPT,
                    "Object storage returned corrupt content", exception);
            case TRANSIENT -> new GitObjectReadException(GitObjectReadException.Reason.TRANSIENT,
                    "Failed to read object", exception);
        };
    }
}
