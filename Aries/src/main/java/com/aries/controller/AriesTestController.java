package com.aries.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AriesTestController {

    private final StringRedisTemplate redisTemplate;

    public AriesTestController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    @GetMapping("/increment")
    public Long increment() {
        return redisTemplate.opsForValue().increment("test_counter");   
    }
}
