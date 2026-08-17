package com.gitnova.storage;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record RepoKey(long ownerId, long repoId) {
    private static final Pattern CANONICAL =
            Pattern.compile("([1-9][0-9]*)/([1-9][0-9]*)");
    public RepoKey{
        if(ownerId<=0) throw new IllegalArgumentException("ownerId must be positive");
        if(repoId<=0) throw new IllegalArgumentException("repoId must be positive");
    }
    public static RepoKey of(long ownerId,long repoId){
        return new RepoKey(ownerId,repoId);
    }
    public static RepoKey parseCanonical(String value){
        Objects.requireNonNull(value,"value must not be null");
        Matcher matcher=CANONICAL.matcher(value);
        if(!matcher.matches()){
            throw new IllegalArgumentException("repoKey must match ownerId/repoId");
        }
        try {
            return new RepoKey(
                    Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2))
            );
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "repoKey contains an invalid identifier",
                    exception
            );
        }
    }
    public String value(){
        return ownerId+"/"+repoId;
    }
}
