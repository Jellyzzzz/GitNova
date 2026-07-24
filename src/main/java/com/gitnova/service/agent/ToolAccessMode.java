package com.gitnova.service.agent;

public enum ToolAccessMode {
    /**
     * 只读取资源，不修改仓库或业务数据。
     */
    READ_ONLY,

    /**
     * 工具执行前需要获得明确审批。
     *
     * 当前 Code Review 阶段暂不使用，
     * 为未来 Apply Fix 等能力预留。
     */
    REQUIRE_APPROVAL
}
