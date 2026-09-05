package com.gitnova.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertyConfiguration.class)
            .withPropertyValues(
                    "gitnova.rate-limit.enabled=true",
                    "gitnova.rate-limit.fail-open=false",
                    "gitnova.rate-limit.user.capacity-permits=10",
                    "gitnova.rate-limit.user.refill-permits-per-second=2",
                    "gitnova.rate-limit.user.request-cost=1",
                    "gitnova.rate-limit.user.idle-ttl=1m",
                    "gitnova.rate-limit.repository.capacity-permits=20",
                    "gitnova.rate-limit.repository.refill-permits-per-second=4",
                    "gitnova.rate-limit.repository.request-cost=2",
                    "gitnova.rate-limit.repository.idle-ttl=1m",
                    "gitnova.rate-limit.api.capacity-permits=30",
                    "gitnova.rate-limit.api.refill-permits-per-second=6",
                    "gitnova.rate-limit.api.request-cost=3",
                    "gitnova.rate-limit.api.idle-ttl=1m"
            );

    @Test
    void shouldBindIndependentDimensionPolicies() {
        contextRunner.run(context -> {
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);

            assertEquals(true, properties.enabled());
            assertEquals(false, properties.failOpen());
            assertEquals(10, properties.user().capacityPermits());
            assertEquals(2, properties.repository().requestCost());
            assertEquals(6D, properties.api().refillPermitsPerSecond());
            assertEquals(Duration.ofMinutes(1), properties.api().idleTtl());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class PropertyConfiguration {
    }
}
