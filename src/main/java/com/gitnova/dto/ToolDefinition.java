package com.gitnova.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;
import java.util.Objects;

/**
 * 暴露给模型的工具定义。
 *
 * @param name        工具唯一名称
 * @param description 工具用途说明
 * @param inputSchema JSON Schema 格式的输入参数定义
 */
public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema
) {
    public ToolDefinition{
        Objects.requireNonNull(name,"name must not be null");
        Objects.requireNonNull(description,"description must not be null");
        Objects.requireNonNull(inputSchema,"inputSchema must not be null");

        if(name.isBlank()){
            throw new IllegalArgumentException("name must not be blank");
        }
        if(description.isBlank()){
            throw new IllegalArgumentException("description must not be blank");
        }
        if(!inputSchema.isObject()){
            throw new IllegalArgumentException("inputSchema must be a JSON object");
        }
    }
}
