package com.gitnova.service.agent;
/**
 * 工具执行结果的机器可读状态。
 *
 * 与异常文本不同，Runtime 可以根据状态决定：
 * 是否重试、是否反馈模型修正参数、是否终止 Run。
 */
public enum ToolStatus {
    /**
     * 工具成功执行。
     */
    SUCCESS,
    /**
     * 模型提供的参数缺失、格式错误或不符合 Schema。
     */
    INVALID_ARGUMENT,
    /**
     * 工具、Revision 或路径访问被权限系统拒绝。
     */
    PERMISSION_DENIED,
    /**
     * 仓库对象、文件或 Revision 不存在。
     */
    NOT_FOUND,
    /**
     * 当前状态与操作要求冲突。
     */
    CONFLICT,
    /**
     * 暂时性故障，例如限流、临时网络或存储错误。
     */
    TRANSIENT_ERROR,
    /**
     * 未预期的内部错误。
     */
    INTERNAL_ERROR
}
