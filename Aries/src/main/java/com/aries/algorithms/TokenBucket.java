package com.aries.algorithms;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TokenBucket {

    private final StringRedisTemplate redisTemplate;

    public TokenBucket(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    // This is class level implementations for Token Bucket Algorithm that isn't made from Lua Script to learn actual things.

    public boolean isAllowed(String userId) {
        long now = System.currentTimeMillis()/1000;
        int maxICanGive = 5;
        int refillRate = 1;

        // Redis implementations strings key value 
        String tokenKey = "token:" + userId;
        String timeKey = "timestamp:" + userId;

        String tokenStr = redisTemplate.opsForValue().get(tokenKey);
        String timeStr  = redisTemplate.opsForValue().get(timeKey);

        Integer tokens = (tokenStr  != null) ? Integer.parseInt(tokenStr) : maxICanGive;
        Long lastRefillTime = (timeStr   != null) ? Long.parseLong(timeStr) : now;

        // Suppose bucket isn't full and user isn't using them too
        long timePassed = now - lastRefillTime;
        int tokensToAdd = (int) (timePassed * refillRate);

        if(tokensToAdd > 0) {
            tokens = Math.min(maxICanGive, tokens + tokensToAdd);
            lastRefillTime = now;
        }

        if(tokens > 0) {
            tokens--;
            redisTemplate.opsForValue().set(tokenKey, tokens.toString());
            redisTemplate.opsForValue().set(timeKey, lastRefillTime.toString());
            return true;
        }
        return false;
    }
}
