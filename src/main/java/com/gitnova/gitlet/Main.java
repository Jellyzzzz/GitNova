package com.gitnova.gitlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.gitobject.CanonicalGitObjectCodec;
import com.gitnova.service.GitletService;

import java.nio.file.Path;

/**
 * Driver class for Gitlet, a subset of the Git version-control system.
 *
 * 保留此文件用于本地命令行测试。
 * Spring Boot 不会扫描到此类作为入口（@SpringBootApplication 扫描 com.gitnova 包，
 * Main 在 gitlet 包下且无 @SpringBootApplication 注解）。
 *
 * Usage: java com.gitnova.gitlet.Main ARGS
 *
 * @author TODO
 */
public class Main {

    private static final String PUSH_TOKEN_ENV = "GITNOVA_TOKEN";
    private static final long PUSH_TIMEOUT_SECONDS = 60;

    /** Usage: java com.gitnova.gitlet.Main ARGS, where ARGS contains
     *  <COMMAND> <OPERAND1> <OPERAND2> ...
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Please enter a command.");
            System.exit(0);
        }

        // 本地测试时使用当前目录作为仓库根路径
        Repository repo = new Repository(System.getProperty("user.dir"));

        int length = args.length;
        String firstArg = args[0];
        switch (firstArg) {
            case "init":
                repo.init();
                break;
            case "add":
                repo.add(args[1]);
                break;
            case "commit":
                repo.commit(args[1]);
                break;
            case "rm":
                repo.remove(args[1]);
                break;
            case "log":
                repo.log();
                break;
            case "global-log":
                repo.globalLog();
                break;
            case "find":
                repo.find(args[1]);
                break;
            case "status":
                repo.status();
                break;
            case "checkout":
                if (length == 2) {
                    String branchname = args[1];
                    repo.checkoutBranch(branchname);
                } else if (length == 3) {
                    if (!args[1].equals("--")) {
                        System.out.println("Incorrect operands.");
                        System.exit(0);
                    }
                    String filename = args[2];
                    repo.checkoutFile(filename);
                } else if (length == 4) {
                    if (!args[2].equals("--")) {
                        System.out.println("Incorrect operands.");
                        System.exit(0);
                    }
                    String commitId = args[1];
                    String filename = args[3];
                    repo.checkoutCommitFile(commitId, filename);
                } else {
                    System.out.println("Incorrect operands.");
                    System.exit(0);
                }
                break;
            case "branch":
                repo.branch(args[1]);
                break;
            case "rm-branch":
                repo.removeBranch(args[1]);
                break;
            case "reset":
                repo.reset(args[1]);
                break;
            case "push":
                push(args);
                break;
            default:
                System.out.println("No command with that name exists.");
                System.exit(0);
        }
    }

    /**
     * Pushes the current repository's committed history to a hosted GitNova repository.
     *
     * <p>Usage: {@code push <serverBaseUrl> <repoId> [branchName]}.</p>
     * The JWT is read from {@code GITNOVA_TOKEN} so it is not exposed through
     * command-line arguments or shell history.
     */
    private static void push(String[] args) {
        if (args.length != 3 && args.length != 4) {
            failPush("Usage: push <serverBaseUrl> <repoId> [branchName]");
            return;
        }

        long repoId;
        try {
            repoId = Long.parseLong(args[2]);
        } catch (NumberFormatException exception) {
            failPush("repoId must be a positive integer.");
            return;
        }
        if (repoId <= 0) {
            failPush("repoId must be a positive integer.");
            return;
        }

        String token = System.getenv(PUSH_TOKEN_ENV);
        if (token == null || token.isBlank()) {
            failPush(PUSH_TOKEN_ENV + " is not set.");
            return;
        }

        Path repositoryRoot = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        Path basePath = repositoryRoot.getParent();
        Path repositoryName = repositoryRoot.getFileName();
        if (basePath == null || repositoryName == null) {
            failPush("The filesystem root cannot be used as a GitNova repository.");
            return;
        }

        try {
            GitletService client = new GitletService(
                    new ObjectMapper(),
                    new CanonicalGitObjectCodec(),
                    basePath.toString(),
                    PUSH_TIMEOUT_SECONDS
            );
            GitletService.PushResult result = client.push(
                    repositoryName.toString(),
                    args[1],
                    repoId,
                    args.length == 4 ? args[3] : "main",
                    token
            );
            System.out.println("Push succeeded.");
            System.out.println("remoteHeadSha1: " + result.remoteHeadSha1());
            System.out.println("objectsUploaded: " + result.objectsUploaded());
            System.out.println("alreadyUpToDate: " + result.alreadyUpToDate());
        } catch (GitletException | IllegalArgumentException exception) {
            failPush(exception.getMessage());
        }
    }

    private static void failPush(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
