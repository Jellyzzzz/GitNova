package com.gitnova.config;

import com.gitnova.dto.ApiResponse;
import com.gitnova.gitobject.CommitCodecException;
import com.gitnova.gitlet.GitletException;
import com.gitnova.service.RepositoryAccessService;
import com.gitnova.service.TransferRejectedException;
import com.gitnova.storage.ObjectStorageException;
import com.gitnova.transfer.PackFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler({PackFormatException.class, CommitCodecException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleMalformedTransfer(RuntimeException ex) {
        log.warn("Malformed Git transfer: {}", ex.getMessage());
        return ApiResponse.error(400, ex.getMessage());
    }

    @ExceptionHandler(TransferRejectedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleTransferRejected(TransferRejectedException ex) {
        log.warn("Push rejected [{}]: {}", ex.reason(), ex.getMessage());
        return ApiResponse.error(409, ex.getMessage());
    }

    @ExceptionHandler(ObjectStorageException.class)
    public ResponseEntity<ApiResponse<?>> handleObjectStorage(ObjectStorageException ex) {
        HttpStatus status = ex.reason() == ObjectStorageException.Reason.TRANSIENT
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        log.warn("Object storage failure [{}]: {}", ex.reason(), ex.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), ex.getMessage()));
    }

    @ExceptionHandler(RepositoryAccessService.AccessException.class)
    public ResponseEntity<ApiResponse<?>> handleRepositoryAccess(
            RepositoryAccessService.AccessException exception
    ) {
        HttpStatus status = switch (exception.reason()) {
            case REPOSITORY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(status.value(), exception.getMessage()));
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
