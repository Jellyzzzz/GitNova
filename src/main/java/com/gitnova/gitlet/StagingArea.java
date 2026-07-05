package com.gitnova.gitlet;

import java.io.File;
import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.gitnova.gitlet.Utils.writeObject;

/**
 * 暂存区 — 管理 add/rm 的文件变更，commit 时写入对象库并清空
 *
 * 重构：INDEX_FILE 不再硬编码为 static final，改为由 Repository 通过构造函数注入。
 */
public class StagingArea implements Serializable {

    /** 外部传入的 index 文件路径（由 Repository 提供） */
    private final File indexFile;

    /** 暂存区新增：文件名 → Blob SHA-1 */
    private Map<String, String> addition;
    /** 暂存区删除：待删除的文件名集合 */
    private Set<String> removal;

    public StagingArea(File indexFile) {
        this.indexFile = indexFile;
        this.addition = new HashMap<>();
        this.removal = new HashSet<>();
    }

    public Map<String, String> getAddition() { return this.addition; }
    public Set<String> getRemoval() { return this.removal; }

    public Map<String, String> putAddition(String fileName, String objectHash) {
        addition.put(fileName, objectHash);
        return this.addition;
    }

    public Set<String> putRemoval(String fileName) {
        removal.add(fileName);
        return this.removal;
    }

    public Map<String, String> removeAddition(String filename) {
        addition.remove(filename);
        return this.addition;
    }

    public Set<String> removeRemoval(String filename) {
        removal.remove(filename);
        return this.removal;
    }

    public boolean containsInAddition(String filename) { return addition.containsKey(filename); }
    public boolean containsInRemoval(String filename) { return removal.contains(filename); }

    public void clearAddition() { addition.clear(); }
    public void clearRemoval() { removal.clear(); }

    public void saveStagingArea() {
        writeObject(indexFile, this);
    }

    public void clearStagingArea() {
        clearAddition();
        clearRemoval();
    }
}
