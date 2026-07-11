package com.gitnova.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 对象协商响应体 — Phase 2
 *
 * POST /api/repos/{repoId}/push/negotiate 的响应体
 */
@Data
@AllArgsConstructor
public class NegotiationResponse {

    /** 服务端当前 HEAD commit SHA-1（null = 空仓库，无任何 commit） */
    private String remoteHeadSha1;

    /** 服务端缺失的对象 SHA-1 列表（[] = 完全同步，跳过 transfer） */
    private List<String> missingObjects;
}
