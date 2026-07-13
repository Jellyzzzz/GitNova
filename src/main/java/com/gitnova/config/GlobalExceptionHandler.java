package com.gitnova.config;

import com.gitnova.dto.ApiResponse;
import com.gitnova.gitlet.GitletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 *
 * 将 GitletException 转换为 HTTP 400 响应，替代原 Gitlet 的 System.exit(0)。
 * 未来可在此追加更多异常映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Gitlet 业务异常 → 400 Bad Request
     */
    @ExceptionHandler(GitletException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleGitletException(GitletException ex) {
        log.warn("Gitlet error: {}", ex.getMessage());
        return ApiResponse.error(400, ex.getMessage());
    }

    /**
     * 通用异常 → 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return ApiResponse.error(500, "Internal server error: " + ex.getMessage());
    }
    /**
     * 参数与业务校验异常 → 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        return ApiResponse.error(400, ex.getMessage());
    }
}
