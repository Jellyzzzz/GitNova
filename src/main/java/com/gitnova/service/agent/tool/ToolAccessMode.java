package com.gitnova.service.agent.tool;

public enum ToolAccessMode {
    /**
     * 只读取资源，不修改仓库或业务数据。
     */
    READ_ONLY,

    /**
     * Mutates only the isolated, Session-scoped Workspace.
     *
     * <p>This does not authorize publishing changes to a repository Ref.</p>
     */
    WORKSPACE_WRITE,

    /**
     * 工具执行前需要获得明确审批，通常用于产生外部可见副作用。
     *
     * 当前 Code Review 阶段暂不使用，
     * 为未来 Apply Fix 等能力预留。
     */
    REQUIRE_APPROVAL
}
