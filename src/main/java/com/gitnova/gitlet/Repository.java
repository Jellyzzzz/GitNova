package com.gitnova.gitlet;

import java.io.File;
import java.util.*;

import static com.gitnova.gitlet.Utils.*;

/**
 * Represents a gitlet repository.
 *
 * 重构要点（GitNova 集成）：
 * 1. 所有 static final File 改为实例字段，通过构造函数接受外部 basePath
 * 2. 所有 static 方法改为实例方法
 * 3. 所有 System.exit(0) 替换为 throw new GitletException(msg)
 * 4. Commit / StagingArea 的存储路径由 Repository 注入，消除硬编码
 *
 * @author TODO
 */
public class Repository {

    // ===== 实例字段（替代原来的 static final） =====

    /** 仓库工作目录根路径（如 /data/gitnova-repos/1/myProject） */
    private final File repoRoot;

    /** .gitlet 目录 */
    private final File gitletDir;

    /** .gitlet/objects 目录 */
    private final File objectsDir;

    /** .gitlet/objects/commits 目录 */
    private final File commitsDir;

    /** .gitlet/objects/blobs 目录 */
    private final File blobsDir;

    /** .gitlet/HEAD 文件 */
    private final File headFile;

    /** .gitlet/branches 目录 */
    private final File branchesDir;

    /** .gitlet/branches/master 文件 */
    private final File masterFile;

    /** .gitlet/index 文件（暂存区序列化文件） */
    private final File indexFile;

    // ===== 构造函数 =====

    /**
     * 创建一个 Gitlet 仓库实例
     *
     * @param basePath 仓库工作目录的绝对路径
     *                 （例如：/data/gitnova-repos/1/myProject）
     */
    public Repository(String basePath) {
        this.repoRoot = new File(basePath);
        this.gitletDir = new File(repoRoot, ".gitlet");
        this.objectsDir = new File(gitletDir, "objects");
        this.commitsDir = new File(objectsDir, "commits");
        this.blobsDir = new File(objectsDir, "blobs");
        this.headFile = new File(gitletDir, "HEAD");
        this.branchesDir = new File(gitletDir, "branches");
        this.masterFile = new File(branchesDir, "master");
        this.indexFile = new File(gitletDir, "index");
    }

    // ===== 公开 API =====

    public void init() {
        if (gitletDir.exists()) {
            throw new GitletException(
                "A Gitlet version-control system already exists in the current directory.");
        } else {
            gitletDir.mkdir();
        }
        if (!objectsDir.exists()) {
            objectsDir.mkdir();
        }
        if (!commitsDir.exists()) {
            commitsDir.mkdir();
        }
        if (!blobsDir.exists()) {
            blobsDir.mkdir();
        }
        if (!branchesDir.exists()) {
            branchesDir.mkdir();
        }
        StagingArea stagingArea = new StagingArea(indexFile);
        writeObject(indexFile, stagingArea);

        writeContents(masterFile, makeInitialCommit("initial commit", null));

        writeContents(headFile, "master");
    }

    public void add(String fileName) {
        File tarFile = join(repoRoot, fileName);
        if (!tarFile.exists()) {
            throw new GitletException("File does not exist.");
        }
        // 计算哈希
        byte[] content = readContents(tarFile);
        String fileHash = sha1(serialize(content));

        // 暂存区存映射
        StagingArea stagingArea = readObject(indexFile, StagingArea.class);
        stagingArea.removeRemoval(fileName);
        Commit currCommit = readCommitHead();
        if (fileHash.equals(currCommit.getMapping().get(fileName))) {
            stagingArea.removeAddition(fileName);
        } else {
            stagingArea.putAddition(fileName, fileHash);
            // 存 blobs
            File newblob = join(blobsDir, fileHash);
            writeContents(newblob, content);
        }
        stagingArea.saveStagingArea();
    }

    public String commit(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new GitletException("Please enter a commit message.");
        }
        String path = readContentsAsString(headFile);
        File currBranch = join(branchesDir, path);
        String parentHash = readContentsAsString(currBranch);
        StagingArea stagingArea = readObject(indexFile, StagingArea.class);
        if (stagingArea.getAddition().isEmpty() && stagingArea.getRemoval().isEmpty()) {
            throw new GitletException("No changes added to the commit.");
        }
        String newCommitHash = makeCommit(message, parentHash, stagingArea);
        // 移动指针
        writeContents(currBranch, newCommitHash);
        // 清理暂存区
        stagingArea.clearStagingArea();
        stagingArea.saveStagingArea();
        return newCommitHash;
    }

    public void remove(String fileName) {
        File tarFile = join(repoRoot, fileName);
        StagingArea stagingArea = readObject(indexFile, StagingArea.class);
        Commit currCommit = readCommitHead();
        Map<String, String> tempBlob = currCommit.getMapping();
        if (!tempBlob.containsKey(fileName) && !stagingArea.containsInAddition(fileName)) {
            throw new GitletException("No reason to remove the file.");
        }
        if (stagingArea.containsInAddition(fileName)) {
            stagingArea.removeAddition(fileName);
        }
        if (tempBlob.containsKey(fileName)) {
            stagingArea.putRemoval(fileName);
            restrictedDelete(tarFile);
        }
        stagingArea.saveStagingArea();
    }

    public void log() {
        String currHash = readContentsAsString(masterFile);
        Commit currCommit = readCommitHead();
        while (currCommit != null) {
            System.out.print("===" + "\n" + currCommit.toString(currHash));
            currHash = currCommit.getParentCommit();
            currCommit = readCommitfromCommits(currHash);
        }
    }

    public void globalLog() {
        List<String> commitList = plainFilenamesIn(commitsDir);
        if (commitList == null) {
            throw new GitletException("You should make commit");
        }
        for (String commitHash : commitList) {
            Commit currCommit = readCommitfromCommits(commitHash);
            if (currCommit != null) {
                System.out.println("===" + "\n" + currCommit.toString(commitHash));
            }
        }
    }

    public void find(String message) {
        boolean founded = false;
        List<String> commitList = plainFilenamesIn(commitsDir);
        if (commitList == null) {
            throw new GitletException("You should make commit");
        }
        for (String commitHash : commitList) {
            Commit currCommit = readCommitfromCommits(commitHash);
            if (message.equals(currCommit.getMessage())) {
                founded = true;
                System.out.println(commitHash);
            }
        }
        if (!founded) {
            throw new GitletException("Found no commit with that message.");
        }
    }

    public void status() {
        String currBranch = readContentsAsString(headFile);
        StagingArea stagingArea = readObject(indexFile, StagingArea.class);
        Commit currCommit = readCommitHead();
        // 打印当前分支
        List<String> branch = plainFilenamesIn(branchesDir);
        Collections.sort(branch);
        System.out.println("=== Branches ===");
        System.out.println("*" + currBranch);
        for (String otherBranch : branch) {
            if (!otherBranch.equals(currBranch)) {
                System.out.println(otherBranch);
            }
        }
        System.out.println();
        // 处理 Addition
        System.out.println("=== Staged Files ===");
        Set<String> currAddition = stagingArea.getAddition().keySet();
        List<String> addition = new ArrayList<>(currAddition);
        Collections.sort(addition);
        for (String filename : addition) {
            System.out.println(filename);
        }
        System.out.println();
        // 处理 Removal
        System.out.println("=== Removed Files ===");
        List<String> removal = new ArrayList<>(stagingArea.getRemoval());
        Collections.sort(removal);
        for (String filename : removal) {
            System.out.println(filename);
        }
        System.out.println();

        // 处理 tracking 文件相关内容
        System.out.println("=== Modifications Not Staged For Commit ===");
        List<String> res = new ArrayList<>();
        List<String> currCwd = plainFilenamesIn(repoRoot);
        // 已被跟踪
        for (String filename : currCommit.getMapping().keySet()) {
            File tarFile = join(repoRoot, filename);
            if (tarFile.exists()) {
                byte[] content = readContents(tarFile);
                String fileHash = sha1(serialize(content));
                if (!currAddition.contains(filename)
                    && !currCommit.getMapping().get(filename).equals(fileHash)) {
                    res.add(filename + " (modified)");
                }
            } else if (!removal.contains(filename)) {
                res.add(filename + " (deleted)");
            }
        }
        // 未被跟踪
        for (String filename : addition) {
            File tarFile = join(repoRoot, filename);
            if (tarFile.exists()) {
                byte[] content = readContents(tarFile);
                String fileHash = sha1(serialize(content));
                if (!stagingArea.getAddition().get(filename).equals(fileHash)) {
                    res.add(filename + " (modified)");
                }
            } else {
                res.add(filename + " (deleted)");
            }
        }
        Collections.sort(res);
        for (String state : res) {
            System.out.println(state);
        }
        System.out.println();
        // 处理未被跟踪
        System.out.println("=== Untracked Files ===");
        for (String filename : currCwd) {
            if (!currCommit.getMapping().containsKey(filename) && !currAddition.contains(filename)) {
                System.out.println(filename);
            } else if (removal.contains(filename)) {
                System.out.println(filename);
            }
        }
        System.out.println();
    }

    public void checkoutFile(String filename) {
        Commit currCommit = readCommitHead();
        String fileHash;
        if (!currCommit.getMapping().containsKey(filename)) {
            throw new GitletException("File does not exist in that commit.");
        } else {
            fileHash = currCommit.getMapping().get(filename);
        }
        byte[] contents = readContents(join(blobsDir, fileHash));
        File tarFile = join(repoRoot, filename);
        if (!tarFile.getParentFile().exists()) {
            tarFile.getParentFile().mkdir();
        }
        writeContents(tarFile, contents);
    }

    public void checkoutCommitFile(String commitId, String filename) {
        boolean founded = false;
        List<String> currCommits = plainFilenamesIn(commitsDir);
        String fileHash = "";
        String commitHash = "";
        for (String hash : currCommits) {
            if (hash.startsWith(commitId)) {
                founded = true;
                commitHash = hash;
                break;
            }
        }
        if (!founded) {
            throw new GitletException("No commit with that id exists.");
        }
        Commit tarcommit = readCommitfromCommits(commitHash);
        if (!tarcommit.getMapping().containsKey(filename)) {
            throw new GitletException("File does not exist in that commit.");
        } else {
            fileHash = tarcommit.getMapping().get(filename);
        }
        byte[] contents = readContents(join(blobsDir, fileHash));
        File tarFile = join(repoRoot, filename);
        if (!tarFile.getParentFile().exists()) {
            tarFile.getParentFile().mkdir();
        }
        writeContents(tarFile, contents);
    }

    public void checkoutBranch(String branchname) {
        StagingArea stagingArea = readObject(indexFile, StagingArea.class);
        List<String> branchList = plainFilenamesIn(branchesDir);
        if (!branchList.contains(branchname)) {
            throw new GitletException("No such branch exists.");
        }
        String tarHash = readContentsAsString(join(branchesDir, branchname));
        String currbranch = readContentsAsString(headFile);
        if (currbranch.equals(branchname)) {
            throw new GitletException("No need to checkout the current branch.");
        }
        Commit tarCommit = readCommitfromCommits(tarHash);
        Commit currCommit = readCommitHead();

        switchWorkingTree(currCommit, tarCommit, stagingArea);
        writeContents(headFile, branchname);
    }

    public void branch(String branchname) {
        List<String> branchList = plainFilenamesIn(branchesDir);
        if (branchList.contains(branchname)) {
            throw new GitletException("A branch with that name already exists.");
        }
        String currBranchName = readContentsAsString(headFile);
        File currBranch = join(branchesDir, currBranchName);
        String headCommitHash = readContentsAsString(currBranch);
        writeContents(join(branchesDir, branchname), headCommitHash);
    }

    public void removeBranch(String branchname) {
        List<String> branchList = plainFilenamesIn(branchesDir);
        String currBranch = readContentsAsString(headFile);
        if (!branchList.contains(branchname)) {
            throw new GitletException("A branch with that name does not exist.");
        }
        if (currBranch.equals(branchname)) {
            throw new GitletException("Cannot remove the current branch.");
        }
        File tarbranch = join(branchesDir, branchname);
        restrictedDelete(tarbranch);
    }

    public void reset(String commitId) {
        StagingArea stagingArea = readObject(indexFile, StagingArea.class);
        boolean founded = false;
        List<String> currCommits = plainFilenamesIn(commitsDir);
        String commitHash = "";
        if (currCommits != null) {
            for (String hash : currCommits) {
                if (hash.startsWith(commitId)) {
                    founded = true;
                    commitHash = hash;
                    break;
                }
            }
        }
        if (!founded) {
            throw new GitletException("No commit with that id exists.");
        }
        Commit currCommit = readCommitHead();
        Commit tarCommit = readCommitfromCommits(commitHash);
        switchWorkingTree(currCommit, tarCommit, stagingArea);

        String currBranch = readContentsAsString(headFile);
        writeContents(join(branchesDir, currBranch), commitHash);
    }

    // ===== 公开的查询辅助方法（供 GitletService 调用） =====

    /** 获取当前 HEAD commit 的 SHA-1 */
    public String getHeadSha1() {
        String branchName = readContentsAsString(headFile);
        return readContentsAsString(join(branchesDir, branchName));
    }

    /** 获取仓库根目录 */
    public File getRepoRoot() {
        return repoRoot;
    }

    /** 获取 .gitlet 目录 */
    public File getGitletDir() {
        return gitletDir;
    }

    /** 获取 objects 目录 */
    public File getObjectsDir() {
        return objectsDir;
    }

    /** 检查 blob 对象是否存在 */
    public boolean blobExists(String sha1) {
        return join(blobsDir, sha1).exists();
    }

    /** 检查 commit 对象是否存在 */
    public boolean commitExists(String sha1) {
        return join(commitsDir, sha1).exists();
    }

    /** 读取一个 blob 的内容 */
    public byte[] readBlob(String sha1) {
        return readContents(join(blobsDir, sha1));
    }

    /** 读取一个 commit 对象 */
    public Commit readCommit(String sha1) {
        return readCommitfromCommits(sha1);
    }

    /** 获取当前 HEAD commit */
    public Commit getHeadCommit() {
        return readCommitHead();
    }

    // ===== 内部方法 =====

    private void switchWorkingTree(Commit currCommit, Commit tarCommit, StagingArea stagingArea) {
        List<String> cwdlist = plainFilenamesIn(repoRoot);
        for (String filename : cwdlist) {
            if (!currCommit.getMapping().containsKey(filename)
                && tarCommit.getMapping().containsKey(filename)
                && !stagingArea.containsInAddition(filename)) {
                throw new GitletException(
                    "There is an untracked file in the way; delete it, or add and commit it first.");
            }
        }

        for (String filename : tarCommit.getMapping().keySet()) {
            String fileHash = tarCommit.getMapping().get(filename);
            byte[] contents = readContents(join(blobsDir, fileHash));
            File tarFile = join(repoRoot, filename);
            if (!tarFile.getParentFile().exists()) {
                tarFile.getParentFile().mkdirs();
            }
            writeContents(tarFile, contents);
        }

        for (String filename : currCommit.getMapping().keySet()) {
            if (!tarCommit.getMapping().containsKey(filename)) {
                File file = join(repoRoot, filename);
                if (file.exists()) {
                    restrictedDelete(file);
                }
            }
        }
        StagingArea newStagingArea = new StagingArea(indexFile);
        newStagingArea.saveStagingArea();
    }

    private String makeInitialCommit(String message, String parentCommit) {
        Commit commit = new Commit(message, parentCommit);
        return commit.saveCommit(commitsDir);
    }

    private String makeCommit(String message, String parentCommit, StagingArea stagingArea) {
        Commit newCommit = new Commit(message, parentCommit);
        Commit parent = readCommitfromCommits(parentCommit);
        Map<String, String> temp = new HashMap<>(parent.getMapping());
        temp.putAll(stagingArea.getAddition());

        Set<String> oldRemoval = stagingArea.getRemoval();
        for (String fileName : oldRemoval) {
            temp.remove(fileName);
        }
        newCommit.setMapping(temp);
        return newCommit.saveCommit(commitsDir);
    }

    private Commit readCommitHead() {
        String path = readContentsAsString(headFile);
        String currHash = readContentsAsString(join(branchesDir, path));
        return readObject(join(commitsDir, currHash), Commit.class);
    }

    private Commit readCommitfromCommits(String commitHash) {
        if (commitHash == null) {
            return null;
        }
        return readObject(join(commitsDir, commitHash), Commit.class);
    }
}
