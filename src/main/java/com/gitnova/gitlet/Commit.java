package com.gitnova.gitlet;

import java.io.File;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

import static com.gitnova.gitlet.Utils.*;

/**
 * Represents a gitlet commit object.
 * DAG 提交图节点 — 每个 Commit 指向一个文件快照 (mapping) 和父 Commit。
 *
 * @author TODO
 */
public class Commit implements Serializable {

    private String timestamp;
    private String parentCommit;
    /** 文件路径 → Blob SHA-1 的映射（当前 commit 的文件快照） */
    private Map<String, String> mapping;
    /** The message of this Commit. */
    private String message;

    public Commit(String message, String parentCommit) {
        this.message = message;
        this.parentCommit = parentCommit;
        this.mapping = new HashMap<>();
        if (this.parentCommit == null) {
            this.timestamp = Utils.timeConvert(Instant.EPOCH);
        } else {
            this.timestamp = Utils.timeConvert(Instant.now());
        }
    }

    public String getMessage() {
        return this.message;
    }

    public String getParentCommit() {
        return this.parentCommit;
    }

    public Map<String, String> getMapping() {
        return mapping;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setParentCommit(String parentCommit) {
        this.parentCommit = parentCommit;
    }

    public void setMapping(Map<String, String> mapping) {
        this.mapping = mapping;
    }

    public String toString(String hash) {
        return "commit" + " " + hash + "\n" + "Date" + ": " + this.timestamp + "\n" + this.message + "\n";
    }

    /**
     * 保存 Commit 到对象库，返回 SHA-1
     *
     * @param commitsDir 外部传入的 commits 目录（由 Repository 提供）
     * @return commit 的 SHA-1 哈希
     */
    public String saveCommit(File commitsDir) {
        String commitHash = sha1(serialize(this));
        File commitFile = join(commitsDir, commitHash);
        writeObject(commitFile, this);
        return commitHash;
    }
}
