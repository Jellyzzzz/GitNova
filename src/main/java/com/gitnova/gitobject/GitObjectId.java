package com.gitnova.gitobject;

import java.util.Objects;
import java.util.regex.Pattern;

public record GitObjectId(String value) {
    private static final Pattern SHA1=Pattern.compile("[0-9a-f]{40}");
    public GitObjectId{
        Objects.requireNonNull(value,"value must not be null");
        if(!SHA1.matcher(value).matches()) throw new IllegalArgumentException("sha1 must be 40 lowercase hexadecimal characters");
    }
    public static GitObjectId of(String value){
        return new GitObjectId(value);
    }
    public static boolean isValid(String value) {
        return value != null && SHA1.matcher(value).matches();
    }
}
