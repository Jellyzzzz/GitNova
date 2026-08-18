package com.gitnova.transfer;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Protocol-internal limits; multipart limits remain an independent HTTP boundary. */
@ConfigurationProperties(prefix = "gitnova.transfer")
@Validated
public record TransferProperties(
        @Min(1) int maxObjectsPerPush,
        @NotNull DataSize maxObjectSize,
        @NotNull DataSize maxPackSize,
        @NotNull DataSize ioBufferSize
) {
    public TransferProperties {
        if (maxObjectSize == null || maxObjectSize.toBytes() <= 0) {
            throw new IllegalArgumentException("maxObjectSize must be positive");
        }
        if (maxPackSize == null || maxPackSize.toBytes() <= 0) {
            throw new IllegalArgumentException("maxPackSize must be positive");
        }
        if (ioBufferSize == null || ioBufferSize.toBytes() <= 0) {
            throw new IllegalArgumentException("ioBufferSize must be positive");
        }
        if (maxObjectSize.toBytes() > maxPackSize.toBytes()) {
            throw new IllegalArgumentException("maxObjectSize must not exceed maxPackSize");
        }
        if (ioBufferSize.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ioBufferSize is too large");
        }
    }
}
