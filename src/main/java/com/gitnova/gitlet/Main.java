package com.gitnova.gitlet;

/**
 * Driver class for Gitlet, a subset of the Git version-control system.
 *
 * 保留此文件用于本地命令行测试。
 * Spring Boot 不会扫描到此类作为入口（@SpringBootApplication 扫描 com.gitnova 包，
 * Main 在 gitlet 包下且无 @SpringBootApplication 注解）。
 *
 * Usage: java gitlet.Main ARGS
 *
 * @author TODO
 */
public class Main {

    /** Usage: java gitlet.Main ARGS, where ARGS contains
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
            default:
                System.out.println("No command with that name exists.");
                System.exit(0);
        }
    }
}
