package com.gitnova.transfer;

import com.gitnova.gitobject.GitObjectId;

import java.nio.file.Path;

/** A verified object body held in a controlled temporary file until Pack promotion. */
public record ValidatedObject(GitObjectId id, Path temporaryFile, long size) {
}
