package com.aries.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;

@Service
public class RateLimiterService {

    private final Counter allowedCounter;
    private final Counter blockedCounter;

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> tokenBucketScript;

    public RateLimiterService(RedisTemplate<String, String> redisTemplate,
            RedisScript<Long> tokenBucketScript, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;

        this.allowedCounter = meterRegistry.counter("rate_limiter.allowed");

        this.blockedCounter = meterRegistry.counter("rate_limiter.blocked");
    }

    public boolean isAllowed(String userId) {

        String key = "rate_limit:" + userId;

        Long result = redisTemplate.execute(
                tokenBucketScript,
                List.of(key),
                "5",
                "1",
                "1000",
                String.valueOf(System.currentTimeMillis()));

        boolean allowed = result != null && result == 1;

        if (allowed) {
            allowedCounter.increment();
        } else {
            blockedCounter.increment();
        }
        return allowed;
    }
}