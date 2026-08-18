package com.gitnova.transfer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TransferProperties.class)
public class TransferConfiguration {
}
