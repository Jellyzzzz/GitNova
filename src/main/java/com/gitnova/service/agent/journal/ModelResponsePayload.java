package com.gitnova.service.agent.journal;

import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.model.ModelFinishReason;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.ModelUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ModelResponsePayload(
        String modelCallId,
        String responseId,
        String text,
        List<ToolCall>toolCalls,
        ModelUsage usage,
        ModelFinishReason finishReason
) {
    public ModelResponsePayload{
        requireNonBlank(modelCallId,"modelCallId");
        requireNonBlank(responseId,"responseId");

        List<ToolCall>result=new ArrayList<>();
        for(ToolCall toolCall:toolCalls){
            Objects.requireNonNull(toolCall,"toolCall must not be null");
            result.add(new ToolCall(toolCall.id(),toolCall.name(),toolCall.arguments().deepCopy()));
        }
        toolCalls=List.copyOf(result);

        if(finishReason==ModelFinishReason.TOOL_CALLS&&toolCalls.isEmpty()) throw new IllegalArgumentException("TOOL_CALLS finish reason requires at least one tool call");
        if(finishReason!=ModelFinishReason.TOOL_CALLS&&!toolCalls.isEmpty()) throw new IllegalArgumentException("Tool calls require TOOL_CALLS as the finish reason");

        Objects.requireNonNull(usage,"usage must not be null");
        Objects.requireNonNull(finishReason,"finishReason must not be null");
    }

    public static ModelResponsePayload from(String modelCallId, ModelResponse response){
        return new ModelResponsePayload(modelCallId,response.responseId(),response.text(),response.toolCalls(),response.usage(),response.finishReason());
    }

    private static void requireNonBlank(String value,String field){
        Objects.requireNonNull(value,field+"must not be null");
        if(value.isBlank()) throw new IllegalArgumentException(field+"must not be blank");
    }
}
