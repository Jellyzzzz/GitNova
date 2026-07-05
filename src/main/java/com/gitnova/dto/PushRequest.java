package com.gitnova.dto;

import lombok.Data;

import java.util.List;

/**
 * Push 协商请求体 — Phase 2 对象协商
 *
 * POST /api/repos/{repoId}/push/negotiate 的请求体
 */
@Data
public class PushRequest {

    /** 客户端当前 HEAD commit SHA-1 */
    private String localHeadSha1;

    /** 客户端本地所有对象的 SHA-1 列表（非文件内容，只是哈希字符串） */
    private List<String> localObjects;
}
