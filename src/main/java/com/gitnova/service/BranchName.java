package com.gitnova.service;

/** Strict, repository-local branch-name boundary for hosted push operations. */
public final class BranchName {

    private BranchName() {
    }

    public static String requireValid(String value) {
        if (value == null || value.isBlank() || value.length() > 100
                || value.startsWith("/") || value.endsWith("/")
                || value.startsWith(".") || value.endsWith(".")
                || value.contains("..") || value.contains("//") || value.contains("@{")
                || value.chars().anyMatch(character -> Character.isWhitespace(character)
                        || character == '\\' || character == '~' || character == '^'
                        || character == ':' || character == '?' || character == '*' || character == '[')) {
            throw new IllegalArgumentException("branch name is invalid");
        }
        return value;
    }
}
