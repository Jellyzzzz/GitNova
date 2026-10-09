package com.gitnova.agent.worker.platform;
import com.gitnova.agent.core.tool.ToolTypes;
import com.gitnova.agent.protocol.platform.PlatformOperation;
/** Worker-only port. Its implementation is bound to one accepted command before tools are registered. */
public interface PlatformCapabilities {
 record CallResult(boolean confirmed, String operationId, PlatformOperation.Result result,
                   String code, String message) {}
 CallResult reportProgress(String message, String summary, ToolTypes.Context context);
 CallResult createPullRequest(String title, String body, boolean draft, ToolTypes.Context context);
}
