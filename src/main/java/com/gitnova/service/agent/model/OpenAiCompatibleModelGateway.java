package com.gitnova.service.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import okhttp3.*;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Component
public class OpenAiCompatibleModelGateway implements ModelGateway{
    private final MediaType JSON=MediaType.get("application/json");
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final HttpUrl endpoint;
    private final String apiKey;
    private final ModelThinking defaultThinking;
    private static final int MAX_ERROR_BODY_BYTES=8192;
    private static final int MAX_ERROR_MESSAGE_CHARS=300;
    @Autowired
    public OpenAiCompatibleModelGateway(
            ObjectMapper objectMapper,
            @Value("${gitnova.llm.api-key:}") String apiKey,
            @Value("${gitnova.llm.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${gitnova.llm.timeout:60}") long timeoutSeconds,
            @Value("${gitnova.llm.read-timeout:${gitnova.llm.timeout:60}}") long readTimeoutSeconds,
            @Value("${gitnova.llm.thinking-mode:}") String thinkingMode,
            @Value("${gitnova.llm.reasoning-effort:high}") String reasoningEffort
    ) {
        // OkHttp treats zero as unlimited; the application must keep both deadlines bounded.
        if (timeoutSeconds <= 0 || readTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Model call and read timeouts must be positive seconds");
        }
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.httpClient = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .build();
        this.apiKey = apiKey;
        this.endpoint = toChatCompletionsEndpoint(baseUrl);
        this.defaultThinking = parseThinking(thinkingMode, reasoningEffort);
    }

    public OpenAiCompatibleModelGateway(ObjectMapper objectMapper, String apiKey, String baseUrl,
                                        long timeoutSeconds, long readTimeoutSeconds, String thinkingMode) {
        this(objectMapper, apiKey, baseUrl, timeoutSeconds, readTimeoutSeconds, thinkingMode, null);
    }

    public OpenAiCompatibleModelGateway(ObjectMapper objectMapper, String apiKey, String baseUrl,
                                        long timeoutSeconds, String thinkingMode) {
        this(objectMapper, apiKey, baseUrl, timeoutSeconds, timeoutSeconds, thinkingMode);
    }
    OpenAiCompatibleModelGateway(
            ObjectMapper objectMapper,
            OkHttpClient httpClient,
            String apiKey,
            HttpUrl endpoint
    ) {
        this(objectMapper, httpClient, apiKey, endpoint, null);
    }

    OpenAiCompatibleModelGateway(
            ObjectMapper objectMapper,
            OkHttpClient httpClient,
            String apiKey,
            HttpUrl endpoint,
            String thinkingMode
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.apiKey = apiKey;
        this.endpoint = Objects.requireNonNull(endpoint);
        this.defaultThinking = parseThinking(thinkingMode, null);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ensureConfigured();

        try {
            String requestJson = objectMapper.writeValueAsString(
                    toProviderRequest(request)
            );

            Request httpRequest = new Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestJson, JSON))
                    .build();

            try (Response httpResponse = httpClient.newCall(httpRequest).execute()) {
                try {
                    if (!httpResponse.isSuccessful()) {
                        throw toGatewayFailure(httpResponse);
                    }
                    return parseSuccessfulResponse(httpResponse);
                } catch (ModelGatewayException failure) {
                    // A 200 response can still be invalid. Keep its status and correlation header.
                    if (failure.providerStatusCode() != null) throw failure;
                    throw new ModelGatewayException(failure.errorCode(), failure.getMessage(), failure.retryable(),
                            httpResponse.code(), failure.providerErrorCode(),
                            firstNonBlank(httpResponse.header("x-request-id"), httpResponse.header("x-ds-trace-id")),
                            failure.retryAfter(), failure.getCause(), failure.responseDiagnostic());
                } catch (IOException | IllegalArgumentException failure) {
                    ModelGatewayErrorCode code;
                    if (failure instanceof InterruptedIOException) {
                        code = ModelGatewayErrorCode.TIMEOUT;
                    } else if (failure instanceof JsonProcessingException || failure instanceof IllegalArgumentException) {
                        code = ModelGatewayErrorCode.INVALID_RESPONSE;
                    } else {
                        code = ModelGatewayErrorCode.NETWORK_ERROR;
                    }
                    throw new ModelGatewayException(code, "Model provider response could not be consumed",
                            code != ModelGatewayErrorCode.INVALID_RESPONSE, httpResponse.code(), null,
                            firstNonBlank(httpResponse.header("x-request-id"), httpResponse.header("x-ds-trace-id")),
                            null, failure, code == ModelGatewayErrorCode.INVALID_RESPONSE
                                    ? new ModelGatewayException.ResponseDiagnostic("RESPONSE_DECODING_FAILED", "/", null, null)
                                    : null);
                }
            }

        } catch (ModelGatewayException exception) {
            throw exception;
        } catch (InterruptedIOException exception) {
            throw new ModelGatewayException(
                    ModelGatewayErrorCode.TIMEOUT,
                    "Model provider request timed out",
                    true,
                    exception
            );
        } catch (JsonProcessingException exception) {
            throw new ModelGatewayException(
                    ModelGatewayErrorCode.INVALID_REQUEST,
                    "Model request could not be serialized",
                    false,
                    exception
            );
        } catch (IOException exception) {
            throw new ModelGatewayException(
                    ModelGatewayErrorCode.NETWORK_ERROR,
                    "Model provider network request failed",
                    true,
                    exception
            );
        }
    }

    private void ensureConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelGatewayException(
                    ModelGatewayErrorCode.CONFIGURATION_ERROR,
                    "Model provider API key is not configured",
                    false,
                    null
            );
        }
    }

    private ProviderRequest toProviderRequest(ModelRequest request)
            throws JsonProcessingException {
        // Runtime supplies the frozen controls; standalone callers may use the configured default.
        ModelThinking thinking = request.thinking() == null ? defaultThinking : request.thinking();
        return new ProviderRequest(
                request.model(),
                toProviderMessages(request.messages()),
                toProviderTools(request.tools()),
                request.maxOutputTokens(),
                thinking != null && thinking.enabled() ? null : request.temperature(),
                thinking == null ? null : new ProviderThinking(thinking.mode()),
                thinking == null ? null : thinking.effort(),
                false
        );
    }

    private List<ProviderMessage> toProviderMessages(
            List<ModelMessage> messages
    ) throws JsonProcessingException {
        List<ProviderMessage> result = new ArrayList<>();

        for (ModelMessage message : messages) {
            result.add(toProviderMessage(message));
        }
        return result;
    }

    private ProviderMessage toProviderMessage(ModelMessage message)
            throws JsonProcessingException {

        return switch (message.role()) {
            case SYSTEM, USER -> new ProviderMessage(
                    message.role().name().toLowerCase(),
                    message.content(),
                    null,
                    null,
                    null
            );

            case TOOL -> new ProviderMessage(
                    "tool",
                    message.content(),
                    null,
                    message.toolCallId(),
                    null
            );

            case ASSISTANT -> new ProviderMessage(
                    "assistant",
                    message.content(),
                    toProviderToolCalls(message.toolCalls()),
                    null,
                    message.reasoningContent()
            );
        };
    }

    private List<ProviderToolCall> toProviderToolCalls(
            List<ToolCall> calls
    ) throws JsonProcessingException {
        if (calls.isEmpty()) {
            return null;
        }

        List<ProviderToolCall> result = new ArrayList<>();
        for (ToolCall call : calls) {
            result.add(new ProviderToolCall(
                    call.id(),
                    "function",
                    new ProviderToolCallFunction(
                            call.name(),
                            objectMapper.writeValueAsString(call.arguments())
                    )
            ));
        }
        return result;
    }

    private List<ProviderTool> toProviderTools(
            List<ToolDefinition> tools
    ) {
        if (tools.isEmpty()) {
            return null;
        }

        List<ProviderTool> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            result.add(new ProviderTool(
                    "function",
                    new ProviderFunction(
                            tool.name(),
                            tool.description(),
                            tool.inputSchema()
                    )
            ));
        }
        return result;
    }

    private ModelResponse parseSuccessfulResponse(Response response)
            throws IOException {
        String body = response.body() == null ? null : response.body().string();
        if (body == null || body.isBlank()) {
            throw invalidResponse("EMPTY_RESPONSE_BODY", "/", "Model provider returned an empty response body", null);
        }
        JsonNode root;
        try {
            root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JsonProcessingException failure) {
            throw invalidResponse("RESPONSE_JSON_INVALID", "/", "Response body is not valid JSON", failure);
        }
        if (root == null || !root.isObject()) {
            throw invalidResponse("RESPONSE_ROOT_NOT_OBJECT", "/", "Response body must be a JSON object", null);
        }
        JsonNode id = root.path("id");
        if (!id.isTextual() || id.textValue().isBlank()) {
            throw invalidResponse("RESPONSE_ID_INVALID", "/id", "Response id must be a non-blank string", null);
        }
        String responseId = id.textValue();
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw invalidResponse("CHOICES_INVALID", "/choices", "choices must be a non-empty array", null);
        }
        JsonNode choice = choices.get(0);
        if (!choice.isObject()) {
            throw invalidResponse("CHOICE_NOT_OBJECT", "/choices/0", "choice must be an object", null);
        }
        JsonNode finish = choice.path("finish_reason");
        if (!finish.isTextual() || finish.textValue().isBlank()) {
            throw invalidResponse("FINISH_REASON_INVALID", "/choices/0/finish_reason", "finish_reason must be a non-blank string", null);
        }
        // Resource exhaustion and truncation are provider outcomes, not malformed tool arguments.
        ModelFinishReason finishReason = mapFinishReason(finish.textValue());
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw invalidResponse("MESSAGE_NOT_OBJECT", "/choices/0/message", "message must be an object", null);
        }
        JsonNode contentNode = message.path("content");
        if (!contentNode.isMissingNode() && !contentNode.isNull() && !contentNode.isTextual()) {
            throw invalidResponse("CONTENT_TYPE_INVALID", "/choices/0/message/content", "content must be a string or null", null);
        }
        String text = contentNode.isTextual() ? contentNode.textValue() : null;
        // Protocol continuation data: preserve exactly, including whitespace and empty strings.
        JsonNode reasoningNode = message.path("reasoning_content");
        if (!reasoningNode.isMissingNode() && !reasoningNode.isNull() && !reasoningNode.isTextual()) {
            throw invalidResponse("REASONING_CONTENT_TYPE_INVALID", "/choices/0/message/reasoning_content",
                    "reasoning_content must be a string or null", null);
        }
        String reasoningContent = reasoningNode.isTextual() ? reasoningNode.textValue() : null;
        List<ToolCall> toolCalls = finishReason == ModelFinishReason.LENGTH || finishReason == ModelFinishReason.CONTENT_FILTER
                ? List.of() : parseToolCalls(message.path("tool_calls"));
        ModelUsage usage = parseUsage(root.path("usage"));
        if (finishReason == ModelFinishReason.STOP
                && toolCalls.isEmpty()
                && (text == null || text.isBlank())) {
            throw invalidResponse("EMPTY_STOP_RESPONSE", "/choices/0/message/content",
                    "STOP response must contain assistant text when it has no tool calls", null);
        }
        if (finishReason == ModelFinishReason.TOOL_CALLS && toolCalls.isEmpty()) {
            throw invalidResponse("TOOL_CALLS_MISSING", "/choices/0/message/tool_calls",
                    "TOOL_CALLS finish reason requires at least one tool call", null);
        }
        if (finishReason != ModelFinishReason.TOOL_CALLS && !toolCalls.isEmpty()) {
            throw invalidResponse("TOOL_CALLS_FINISH_REASON_MISMATCH", "/choices/0/finish_reason",
                    "Tool calls require TOOL_CALLS as the finish reason", null);
        }
        return new ModelResponse(responseId, text, toolCalls, usage, finishReason, reasoningContent);
    }

    private ModelGatewayException toGatewayFailure(Response response)
            throws IOException {
        // TODO:
        // - 读取受长度限制的 error body；不要写入普通日志
        String rawBody=null;
        rawBody=readLimitedBody(response.body());

        // - 提取 error.code、x-request-id、Retry-After
        String providerMessage=null;
        String providerErrorCode=null;
    if(rawBody!=null&&!rawBody.isBlank()){
        try{
            JsonNode errorNode=objectMapper.readTree(rawBody).path("error");
            if(errorNode.isObject()){
                providerMessage=errorNode.path("message").asText(null);
                providerErrorCode=firstNonBlank(errorNode.path("code").asText(null),null);
            }
        }catch(IOException e){
            providerMessage=rawBody.trim();
        }
    }
        // - 按 HTTP status 映射 ModelGatewayErrorCode
        String providerRequestId=firstNonBlank(response.header("x-request-id"),response.header("x-ds-trace-id"));
        Duration retryAfter=parseRetryAfter(response.header("Retry-After"));

        int status=response.code();
        ModelGatewayErrorCode errorCode=mapFailureCode(status,providerErrorCode);
        boolean retryable=status==429||status>=500;
        // - 返回 new ModelGatewayException(...)
        return new ModelGatewayException(errorCode,sanitizedMessage(status,providerMessage),retryable,
                status,
                providerErrorCode,
                providerRequestId,
                retryAfter,
                null);
    }
    private List<ToolCall>parseToolCalls(JsonNode toolCallsNode){
        if(toolCallsNode.isMissingNode()|| toolCallsNode.isNull()) return List.of();
        if (!toolCallsNode.isArray()) throw invalidResponse("TOOL_CALLS_NOT_ARRAY",
                "/choices/0/message/tool_calls", "tool_calls must be an array", null);
        ArrayNode array=(ArrayNode) toolCallsNode;
        List<ToolCall> result=new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        for(int i=0;i<array.size();i++){
            String path = "/choices/0/message/tool_calls/" + i;
            ToolCall call = toToolCall(array.get(i), path);
            if (!ids.add(call.id())) throw invalidResponse("DUPLICATE_TOOL_CALL_ID", path + "/id",
                    "tool call ids must be unique within a response", null);
            result.add(call);
        }
        return result;
    }
    private ToolCall toToolCall(JsonNode element, String path){
        if (!element.isObject()) throw invalidResponse("TOOL_CALL_NOT_OBJECT", path, "tool call must be an object", null);
        JsonNode idNode = element.path("id");
        if (!idNode.isTextual() || idNode.textValue().isBlank()) {
            throw invalidResponse("TOOL_CALL_ID_INVALID", path + "/id", "tool call id must be a non-blank string", null);
        }
        if (!element.path("function").isObject()) {
            throw invalidResponse("TOOL_FUNCTION_NOT_OBJECT", path + "/function", "function must be an object", null);
        }
        JsonNode nameNode = element.path("function").path("name");
        if (!nameNode.isTextual() || nameNode.textValue().isBlank()) {
            throw invalidResponse("TOOL_NAME_INVALID", path + "/function/name", "tool name must be a non-blank string", null);
        }
        JsonNode argumentsNode=element.path("function").path("arguments");
        String argumentsText=argumentsNode.isTextual()?argumentsNode.textValue():null;

        if (argumentsText == null || argumentsText.isBlank()) throw invalidResponse("TOOL_ARGUMENTS_STRING_INVALID",
                path + "/function/arguments", "tool call arguments must be a non-blank JSON string", null);

        JsonNode arguments;
        try{
            arguments=objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(argumentsText);
        }catch(JsonProcessingException e){
            throw invalidResponse("TOOL_ARGUMENTS_JSON_INVALID", path + "/function/arguments",
                    "tool call arguments are not valid JSON", e);
        }
        // Shape/field mistakes in valid JSON belong to ToolRegistry so the model gets an Observation.
        return new ToolCall(idNode.textValue(), nameNode.textValue(), arguments);
    }
    private static ModelFinishReason mapFinishReason(String finishReason){
        if(finishReason==null||finishReason.isBlank()) throw invalidResponse("FINISH_REASON_INVALID",
                "/choices/0/finish_reason", "finishReason must not be null", null);
        return switch (finishReason) {
            case "stop" -> ModelFinishReason.STOP;
            case "tool_calls" -> ModelFinishReason.TOOL_CALLS;
            case "length" -> ModelFinishReason.LENGTH;
            case "content_filter" -> ModelFinishReason.CONTENT_FILTER;
            case "insufficient_system_resource" -> throw new ModelGatewayException(
                    ModelGatewayErrorCode.PROVIDER_UNAVAILABLE, "Model provider inference resources are unavailable",
                    true, null, finishReason, null, null, null);
            default -> ModelFinishReason.UNKNOWN;   // 未知值不视为协议违规 —— 枚举里专门有 UNKNOWN
        };
    }
    private static ModelUsage parseUsage(JsonNode usageNode){
        if (usageNode.isMissingNode() || usageNode.isNull()) return ModelUsage.unknown();
        if (!usageNode.isObject()) throw invalidResponse("USAGE_NOT_OBJECT", "/usage", "usage must be an object or null", null);
        return new ModelUsage(nullableInt(usageNode.path("prompt_tokens"), "/usage/prompt_tokens"),
                nullableInt(usageNode.path("completion_tokens"), "/usage/completion_tokens"),
                nullableInt(usageNode.path("total_tokens"), "/usage/total_tokens"));
    }
    private static Integer nullableInt(JsonNode node, String path){
        if(node.isMissingNode()||node.isNull()) return null;
        if(!node.isIntegralNumber()||!node.canConvertToInt()||node.intValue()<0){
            throw invalidResponse("USAGE_VALUE_INVALID", path, "Token usage must be a non-negative integer within range", null);
        }
        return node.intValue();
    }

    private static ModelGatewayException invalidResponse(String reason, String path, String message,
                                                         JsonProcessingException failure) {
        var location = failure == null ? null : failure.getLocation();
        Integer line = location != null && location.getLineNr() > 0 ? location.getLineNr() : null;
        Integer column = location != null && location.getColumnNr() > 0 ? location.getColumnNr() : null;
        // Parser exceptions can retain the raw source. Persist/log only this bounded diagnostic.
        return new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE, message, false,
                null, null, null, null, null,
                new ModelGatewayException.ResponseDiagnostic(reason, path, line, column));
    }

    private String readLimitedBody(ResponseBody body)throws IOException{
        if (body == null) {
            return null;
        }
        BufferedSource source=body.source();
        byte[] buffer=new byte[MAX_ERROR_BODY_BYTES];
        int total=0;
        int read;
        while(total<buffer.length&&(read=source.read(buffer,total,buffer.length-total))!=-1){
            total+=read;
        }
        return new String(buffer,0,total, StandardCharsets.UTF_8);
    }
    private String firstNonBlank(String first,String second){
        if(first!=null&&!first.isBlank())return first;
        if(second!=null&&!second.isBlank()) return second;
        return null;
    }
    private String sanitizedMessage(int status,String providerMessage){
        String base="Model provider returned HTTP "+status;
        if(providerMessage==null||providerMessage.isBlank()) return base;
        String trimmed=providerMessage.length()>MAX_ERROR_MESSAGE_CHARS?providerMessage.substring(0,MAX_ERROR_MESSAGE_CHARS)+"..."
                :providerMessage;
        return base+": "+trimmed;
    }
    private ModelGatewayErrorCode mapFailureCode(int status,String providerErrorCode){
        if((status==400&&"context_length_exceeded".equals(providerErrorCode))||"context_length_error".equals(providerErrorCode)) return ModelGatewayErrorCode.CONTEXT_LENGTH_EXCEEDED;
        return switch (status){
            case 400,422->ModelGatewayErrorCode.INVALID_REQUEST;
            case 401->ModelGatewayErrorCode.AUTHENTICATION_FAILED;
            case 403->ModelGatewayErrorCode.PERMISSION_DENIED;
            case 404->ModelGatewayErrorCode.MODEL_NOT_FOUND;
            case 429->ModelGatewayErrorCode.RATE_LIMITED;
            case 502,503,504->ModelGatewayErrorCode.PROVIDER_UNAVAILABLE;
            default -> status>=500
                    ?ModelGatewayErrorCode.PROVIDER_UNAVAILABLE
                    :ModelGatewayErrorCode.PROVIDER_FAILURE;
        };
    }
    private Duration parseRetryAfter(String value){
        if(value==null||value.isBlank()) return null;
        try{
            long secondes=Long.parseLong(value.trim());
            return secondes>=0?Duration.ofSeconds(secondes):null;
        }catch (NumberFormatException e){
            try{
                ZonedDateTime when=ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration wait=Duration.between(ZonedDateTime.now(),when);
                return wait.isNegative()?null:wait;
            }catch (DateTimeParseException e2){
                return null;
            }
        }
    }
    private static HttpUrl toChatCompletionsEndpoint(String baseUrl) {
        try {
            return HttpUrl.get(baseUrl)
                    .newBuilder()
                    .addPathSegment("chat")
                    .addPathSegment("completions")
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new ModelGatewayException(
                    ModelGatewayErrorCode.CONFIGURATION_ERROR,
                    "Model provider base URL is invalid",
                    false,
                    exception
            );
        }
    }

    private static ModelThinking parseThinking(String thinkingMode, String reasoningEffort) {
        if (thinkingMode == null || thinkingMode.isBlank()) {
            return null;
        }
        try {
            return new ModelThinking(thinkingMode, reasoningEffort);
        } catch (IllegalArgumentException failure) {
            throw new ModelGatewayException(
                    ModelGatewayErrorCode.CONFIGURATION_ERROR,
                    failure.getMessage(),
                    false,
                    failure
            );
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ProviderRequest(
            String model,
            List<ProviderMessage> messages,
            List<ProviderTool> tools,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature,
            ProviderThinking thinking,
            @JsonProperty("reasoning_effort") String reasoningEffort,
            boolean stream
    ) {}

    private record ProviderThinking(String type) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ProviderMessage(
            String role,
            @JsonInclude(JsonInclude.Include.ALWAYS) String content,
            @JsonProperty("tool_calls") List<ProviderToolCall> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId,
            @JsonProperty("reasoning_content") String reasoningContent
    ) {}

    private record ProviderTool(
            String type,
            ProviderFunction function
    ) {}

    private record ProviderFunction(
            String name,
            String description,
            JsonNode parameters
    ) {}

    private record ProviderToolCall(
            String id,
            String type,
            ProviderToolCallFunction function
    ) {}

    private record ProviderToolCallFunction(
            String name,
            String arguments
    ) {}
}
