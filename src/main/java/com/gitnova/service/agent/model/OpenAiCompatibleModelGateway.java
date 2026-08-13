package com.gitnova.service.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class OpenAiCompatibleModelGateway implements ModelGateway{
    private final MediaType JSON=MediaType.get("application/json");
    private ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final HttpUrl endpoint;
    private final String apiKey;
    private static final int MAX_ERROR_BODY_BYTES=8192;
    private static final int MAX_ERROR_MESSAGE_CHARS=300;
    @Autowired
    public OpenAiCompatibleModelGateway(
            ObjectMapper objectMapper,
            @Value("${gitnova.llm.api-key:}") String apiKey,
            @Value("${gitnova.llm.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${gitnova.llm.timeout:60}") long timeoutSeconds
    ) {
        this(
                objectMapper,
                new OkHttpClient.Builder()
                        .callTimeout(Duration.ofSeconds(timeoutSeconds))
                        .build(),
                apiKey,
                toChatCompletionsEndpoint(baseUrl)
        );
    }
    OpenAiCompatibleModelGateway(
            ObjectMapper objectMapper,
            OkHttpClient httpClient,
            String apiKey,
            HttpUrl endpoint
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.apiKey = apiKey;
        this.endpoint = Objects.requireNonNull(endpoint);
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
                if (!httpResponse.isSuccessful()) {
                    throw toGatewayFailure(httpResponse);
                }
                return parseSuccessfulResponse(httpResponse);
            }

        } catch (ModelGatewayException exception) {
            throw exception;
        } catch (SocketTimeoutException exception) {
            throw new ModelGatewayException(
                    ModelGatewayErrorCode.TIMEOUT,
                    "Model provider request timed out",
                    true,
                    exception
            );
        } catch (JsonProcessingException exception) {
            throw new ModelGatewayException(
                    ModelGatewayErrorCode.INVALID_RESPONSE,
                    "Model provider response could not be parsed",
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

        return new ProviderRequest(
                request.model(),
                toProviderMessages(request.messages()),
                toProviderTools(request.tools()),
                request.maxOutputTokens(),
                request.temperature(),
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
                    null
            );

            case TOOL -> new ProviderMessage(
                    "tool",
                    message.content(),
                    null,
                    message.toolCallId()
            );

            case ASSISTANT -> new ProviderMessage(
                    "assistant",
                    message.content(),
                    toProviderToolCalls(message.toolCalls()),
                    null
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
        // 1. body 不得为空；objectMapper.readTree(body.string())
        String body=response.body()==null?null:response.body().string();
        if(body==null||body.isBlank()) throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"Model provider returned an empty response body",false,null);
        // 2. 读取 root.id
        JsonNode root=objectMapper.readTree(body);
        if(!root.isObject()) throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"Response body must be a JSON object",false,null);
        String responseId=root.path("id").asText(null);
        if(responseId==null||responseId.isBlank()) throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"Response is missing a valid id",false,null);
        // 3. 验证 choices 是非空数组，取 choices[0]
        JsonNode choices=root.path("choices");
        if(!choices.isArray()||choices.isEmpty()) throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"choices must be a non-empty array",false,null);
        JsonNode choice=choices.get(0);
        // 4. 读取 message.content
        JsonNode contentNode=choice.path("message").path("content");
        String text=contentNode.isTextual()?contentNode.asText():null;
        // 5. parseToolCalls(message.path("tool_calls"))
        List<ToolCall> toolCalls=parseToolCalls(choice.path("message").path("tool_calls"));
        // 6. 映射 finish_reason
        ModelFinishReason finishReason=mapFinishReason(choice.path("finish_reason").asText(null));
        // 7. 读取 usage
        ModelUsage usage=parseUsage(root.path("usage"));
        if (finishReason == ModelFinishReason.STOP
                && toolCalls.isEmpty()
                && (text == null || text.isBlank())) {
            throw new ModelGatewayException(
                    ModelGatewayErrorCode.INVALID_RESPONSE,
                    "STOP response must contain assistant text when it has no tool calls",
                    false,
                    null
            );
        }
        // 8. 构造 ModelResponse
        try {
            return new ModelResponse(responseId, text, toolCalls, usage, finishReason);
        }catch(IllegalArgumentException e){
            throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"Response violates model invariants",false,e);
        }
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
                providerErrorCode=errorNode.path("code").asText(null);
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
        if(!toolCallsNode.isArray()) throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"toolCallsNode must be an array",false,null);
        ArrayNode array=(ArrayNode) toolCallsNode;
        List<ToolCall> result=new ArrayList<>();
        for(int i=0;i<array.size();i++){
            result.add(toToolCall(array.get(i)));
        }
        return result;
    }
    private  ToolCall toToolCall(JsonNode element){
        String id=element.path("id").asText(null);
        String name=element.path("function").path("name").asText(null);
        String argumentsText=element.path("function").path("arguments").asText(null);

        if(id==null||id.isBlank()||name==null||name.isBlank()) throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"tool call is missing id or function name",false,null);
        if(argumentsText==null) throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"tool call arguments must be a JSON string",false,null);

        JsonNode arguments;
        try{
            arguments=objectMapper.readTree(argumentsText);
        }catch(JsonProcessingException e){
            throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"tool call arguments are not valid JSON",false,null);
        }
        return new ToolCall(id,name,arguments);
    }
    private static ModelFinishReason mapFinishReason(String finishReason){
        if(finishReason==null||finishReason.isBlank()) throw new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,"finishReason must not be null",false,null);
        return switch (finishReason) {
            case "stop" -> ModelFinishReason.STOP;
            case "tool_calls" -> ModelFinishReason.TOOL_CALLS;
            case "length" -> ModelFinishReason.LENGTH;
            case "content_filter" -> ModelFinishReason.CONTENT_FILTER;
            default -> ModelFinishReason.UNKNOWN;   // 未知值不视为协议违规 —— 枚举里专门有 UNKNOWN
        };
    }
    private static ModelUsage parseUsage(JsonNode usageNode){
        if(!usageNode.isObject()){
            return ModelUsage.unknown();
        }
        return new ModelUsage(nullableInt(usageNode.path("prompt_tokens")),nullableInt(usageNode.path("completion_tokens")),nullableInt(usageNode.path("total_tokens")));
    }
    private static Integer nullableInt(JsonNode node){
        return node.isIntegralNumber()?node.intValue():null;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ProviderRequest(
            String model,
            List<ProviderMessage> messages,
            List<ProviderTool> tools,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature,
            boolean stream
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ProviderMessage(
            String role,
            @JsonInclude(JsonInclude.Include.ALWAYS) String content,
            @JsonProperty("tool_calls") List<ProviderToolCall> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId
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
