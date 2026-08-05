package com.gitnova.gitobject;

public class GitObjectReadException extends RuntimeException {
    public GitObjectReadException(String message) {super(message);}
    public GitObjectReadException(String message,Throwable cause) {
        super(message,cause);
    }
}
