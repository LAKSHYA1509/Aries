package com.aries.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> tokenBucketScript;

    public RateLimiterService(RedisTemplate<String, String> redisTemplate,
            RedisScript<Long> tokenBucketScript) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
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

        return result != null && result == 1;
    }
}
