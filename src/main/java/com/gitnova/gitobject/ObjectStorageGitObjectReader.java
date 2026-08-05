package com.gitnova.gitobject;

import com.gitnova.gitlet.Commit;
import com.gitnova.gitlet.Utils;
import com.gitnova.storage.ObjectStorage;
import org.springframework.stereotype.Service;

@Service
public class ObjectStorageGitObjectReader implements GitObjectReader{
    private final ObjectStorage objectStorage;
    public ObjectStorageGitObjectReader(ObjectStorage objectStorage){
        this.objectStorage=objectStorage;
    }
    @Override
    public Commit requireCommit(String repoKey, String sha1) {
        byte[] commit = requireRawObject(repoKey, sha1);
        try {
            return Utils.deserialize(commit, Commit.class);
        }catch (RuntimeException e){
            throw new RuntimeException("Object is not a valid commit",e);
        }
    }

    @Override
    public byte[] requireBlob(String repoKey, String sha1) {
        return requireRawObject(repoKey,sha1);
    }
    private byte[] requireRawObject(String repoKey,String sha1){
        if(repoKey==null||repoKey.isBlank()) throw new IllegalArgumentException("repoKey must not be blank");
        if(sha1==null||sha1.isBlank()) throw new IllegalArgumentException("sha1 must not be blank");
        try{
            byte[] content=objectStorage.readObject(repoKey,sha1);
            if(content==null) throw new GitObjectReadException("ObjectStorage returned null for object: " + sha1);
            return content;
        }catch(GitObjectReadException e){throw e;}
        catch(RuntimeException e){
            throw new GitObjectReadException("Failed to read Object",e);
        }
    }

}
