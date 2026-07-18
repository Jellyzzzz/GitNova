package com.gitnova.dto;

import lombok.Data;

/**
 * 传输元数据 — Phase 2 transfer multipart 中的 metadata part
 *
 * POST /api/repos/{repoId}/push/transfer 的 metadata 字段
 */
@Data
public class TransferMetadata {

    /** 新的 HEAD commit SHA-1 */
    private String newHeadSha1;

    /** 客户端认为的服务端当前 HEAD（CAS 基准值） */
    private String baseHeadSha1;

    /** 目标分支名，默认 main */
    private String branchName = "main";

    /** 提交信息 */
    private String commitMessage;

    /** 是否请求 Code Review（默认 true，review=false 时跳过 Agent） */
    private boolean review = true;
}
