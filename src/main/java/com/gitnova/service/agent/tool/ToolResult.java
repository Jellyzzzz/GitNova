package com.gitnova.service.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/**
 * 工具的结构化执行结果。
 *
 * payload 是提供给模型的机器可读数据；
 * errorCode/message 是提供给 Harness 和模型的错误描述；
 * retryable 表示同一操作稍后重试是否可能成功；
 * truncated 表示 payload 是否因输出上限而被截断。
 * PARTIAL_SUCCESS 表示一个已确认的操作前缀改变了状态，但整个请求未完成；
 * 它必须携带最终权威状态，且不能由 Harness 原样自动重试。
 */
public record ToolResult(ToolStatus status,
                         JsonNode payload,
                         String errorCode,
                         String message,
                         boolean retryable,
                         boolean truncated) {
        public ToolResult{
            Objects.requireNonNull(status,
                    "status must not be null");
            Objects.requireNonNull(payload,"payload must not be null");
            if(status==ToolStatus.SUCCESS){
                if(errorCode!=null||message!=null){
                    throw new IllegalArgumentException(
                            "Successful ToolResult must not contain error information"
                    );
                }
                if(retryable){
                    throw new IllegalArgumentException("Successful ToolResult cannot be retryable");
                }
            }
            else{
                if(errorCode==null||errorCode.isBlank()){
                    throw new IllegalArgumentException("Failed ToolResult must contain errorCode");
                }
                if(message==null||message.isBlank()){
                    throw new IllegalArgumentException("Failed ToolResult must contain message");
                }
                if(status==ToolStatus.PARTIAL_SUCCESS){
                    if(payload.isNull()){
                        throw new IllegalArgumentException(
                                "Partial ToolResult must contain a state payload"
                        );
                    }
                    if(retryable){
                        throw new IllegalArgumentException(
                                "Partial ToolResult cannot be retried unchanged"
                        );
                    }
                }
            }
        }
    public static ToolResult success(JsonNode payload){
            return new ToolResult(
                    ToolStatus.SUCCESS,
                    Objects.requireNonNull(payload,"payload must not be null"),
                    null,
                    null,
                    false,
                    false
            );
    }
    public static ToolResult success(JsonNode payload,boolean truncated){
            return new ToolResult(
                    ToolStatus.SUCCESS,
                    Objects.requireNonNull(payload,"payload must not be null"),
                    null,
                    null,
                    false,
                    truncated
            );
    }
    public static ToolResult error(ToolStatus status,String errorCode,String message,boolean retryable){
            return error(
                    status,
                    NullNode.getInstance(),
                    errorCode,
                    message,
                    retryable,
                    false
            );
    }
    public static ToolResult error(
            ToolStatus status,
            JsonNode payload,
            String errorCode,
            String message,
            boolean retryable
    ) {
            return error(status, payload, errorCode, message, retryable, false);
    }
    public static ToolResult error(
            ToolStatus status,
            JsonNode payload,
            String errorCode,
            String message,
            boolean retryable,
            boolean truncated
    ) {
            Objects.requireNonNull(status, "status must not be null");
            if(status==ToolStatus.SUCCESS) throw new IllegalArgumentException("Error result cannot use SUCCESS status");
            if(status==ToolStatus.PARTIAL_SUCCESS) {
                throw new IllegalArgumentException(
                        "Error result cannot use PARTIAL_SUCCESS status"
                );
            }
            return new ToolResult(
                    status,
                    Objects.requireNonNull(payload, "payload must not be null"),
                    errorCode,
                    message,
                    retryable,
                    truncated
            );
    }
    public static ToolResult partialSuccess(
            JsonNode payload,
            String errorCode,
            String message
    ) {
            return new ToolResult(
                    ToolStatus.PARTIAL_SUCCESS,
                    Objects.requireNonNull(payload, "payload must not be null"),
                    errorCode,
                    message,
                    false,
                    false
            );
    }
    public boolean successful(){
            return status==ToolStatus.SUCCESS;
    }
    /** Returns true only for the explicit, state-bearing partial outcome. */
    public boolean partiallySuccessful(){
            return status==ToolStatus.PARTIAL_SUCCESS;
    }
}
