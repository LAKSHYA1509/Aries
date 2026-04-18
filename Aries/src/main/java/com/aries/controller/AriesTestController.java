package com.aries.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aries.service.RateLimiterService;

@RestController
public class AriesTestController {

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterService rateLimiterService;

    public AriesTestController(StringRedisTemplate redisTemplate, RateLimiterService rateLimiterService) {
        this.redisTemplate = redisTemplate;
        this.rateLimiterService = rateLimiterService;
    }
    
    @GetMapping("/increment")
    public Long increment() {
        return redisTemplate.opsForValue().increment("test_counter");   
    }

    @GetMapping("/ratecheck")
    public ResponseEntity<String> rateCheck() {
        boolean check = rateLimiterService.isAllowed("user1");
        if(check) {
            return ResponseEntity.ok("Here is your Data");
        }
        else return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Too many requests");
    }
}
