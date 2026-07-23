package com.gitnova.service.agent;

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
            Objects.requireNonNull(status, "status must not be null");
            if(status==ToolStatus.SUCCESS) throw new IllegalArgumentException("Error result cannot use SUCCESS status");
            return new ToolResult(status, NullNode.getInstance(),errorCode,message,retryable,false);
    }
    public boolean successful(){
            return status==ToolStatus.SUCCESS;
    }
}
