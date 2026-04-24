# 🏗️ System Architecture & Code Deep Dive

> Line-by-line analysis of every class, every annotation, and every design pattern used in Aries.

---

## 1. Package Structure

```
com.aries/
├── AriesApplication.java           ← Spring Boot entry point
├── algorithms/
│   └── TokenBucket.java            ← Educational Java implementation (non-production path)
├── config/
│   └── RedisConfig.java            ← Bean definitions / configuration classes
├── controller/
│   └── AriesTestController.java    ← REST HTTP controller for debugging/testing
└── service/
    ├── GrpcRateLimiterService.java ← gRPC server implementation (primary API)
    └── RateLimiterService.java     ← Core business logic (Lua + Metrics)

resources/
├── application.yml                 ← Spring Boot externalized configuration
└── tokens_bucket.lua               ← Production atomic rate limiting script

proto/
└── rate_limiter.proto              ← gRPC service contract (source of truth)
```

---

## 2. `AriesApplication.java` — The Entry Point

```java
package com.aries;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AriesApplication {
    public static void main(String[] args) {
        SpringApplication.run(AriesApplication.class, args);
    }
}
```

### `@SpringBootApplication` Decomposed

```java
@SpringBootApplication
= @Configuration        // This class is a source of bean definitions
+ @EnableAutoConfiguration // Scan classpath, auto-configure matching beans
                           // (Redis, gRPC, Actuator, Prometheus — all auto-detected)
+ @ComponentScan         // Scan com.aries and all sub-packages for @Component, @Service, etc.
```

### Spring Boot Startup Sequence

```
1. main() calls SpringApplication.run()
2. Creates ApplicationContext (Spring IoC container)
3. Scans classpath via @ComponentScan
4. @EnableAutoConfiguration fires:
   - Detects spring-boot-starter-data-redis → configures RedisTemplate, Lettuce pool
   - Detects grpc-spring-boot-starter → starts Netty gRPC server on port 9090
   - Detects micrometer-registry-prometheus → configures Prometheus MeterRegistry
   - Detects spring-boot-starter-actuator → exposes /actuator/* endpoints
   - Detects spring-boot-starter-web → starts embedded Tomcat on port 8000
5. Initializes all beans (RedisConfig, RateLimiterService, GrpcRateLimiterService, etc.)
6. Application ready — both gRPC (9090) and HTTP (8000) servers running
```

---

## 3. `RedisConfig.java` — Configuration Class

```java
package com.aries.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<Long> tokenBucketScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("tokens_bucket.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
```

### Deep Analysis

**`@Configuration`**: Marks this as a bean definition class. All `@Bean` methods here will be processed by the Spring IoC container. Spring creates CGLIB proxies of `@Configuration` classes to ensure `@Bean` methods return singleton instances.

**`@Bean`**: Registers the return value as a Spring-managed singleton bean in the ApplicationContext. Name of bean = method name = `"tokenBucketScript"`.

**`ClassPathResource("tokens_bucket.lua")`**: Spring's abstraction over classpath resources. Looks for `tokens_bucket.lua` in:
- `src/main/resources/` (dev/test)
- `<jar>/BOOT-INF/classes/` (production JAR)

**`DefaultRedisScript<Long>`**: Spring Data's wrapper for Redis scripts that:
1. Stores the script content
2. Computes SHA1 hash for `EVALSHA`
3. Handles serialization of return type

**`setResultType(Long.class)`**: The Lua script returns `{allowed}` (a Lua table with one element). Spring maps `table[1]` → `Long`. Returns `1L` for allowed, `0L` for blocked.

### Dependency Injection Chain

```
RedisConfig.@Bean tokenBucketScript()
    ↓ injected into ↓
RateLimiterService(RedisTemplate<String,String>, RedisScript<Long>, MeterRegistry)
    ↓ injected into ↓
GrpcRateLimiterService(RateLimiterService)
AriesTestController(StringRedisTemplate, RateLimiterService)
```

---

## 4. `RateLimiterService.java` — Core Business Logic

```java
@Service
public class RateLimiterService {

    private final Counter allowedCounter;
    private final Counter blockedCounter;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> tokenBucketScript;

    public RateLimiterService(
            RedisTemplate<String, String> redisTemplate,
            RedisScript<Long> tokenBucketScript,
            MeterRegistry meterRegistry) {
        
        this.redisTemplate     = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        
        // Register counters at construction time
        this.allowedCounter = meterRegistry.counter("rate_limiter.allowed");
        this.blockedCounter = meterRegistry.counter("rate_limiter.blocked");
    }

    public boolean isAllowed(String userId) {
        String key = "rate_limit:" + userId;  // Redis key pattern

        Long result = redisTemplate.execute(
            tokenBucketScript,    // Script bean
            List.of(key),         // KEYS[1] in Lua
            "5",                  // ARGV[1] = capacity
            "1",                  // ARGV[2] = refill_rate
            "1000",               // ARGV[3] = refill_interval (1000ms)
            String.valueOf(System.currentTimeMillis())  // ARGV[4] = now in ms
        );

        boolean allowed = result != null && result == 1;

        if (allowed) {
            allowedCounter.increment();
        } else {
            blockedCounter.increment();
        }
        return allowed;
    }
}
```

### `@Service` vs `@Component`

Both register the class as a Spring bean. `@Service` is a **semantic annotation** — it indicates this is a service-layer class (business logic). It's detected by `@ComponentScan`.

### Constructor Injection vs Field Injection

```java
// ❌ Field Injection (anti-pattern)
@Autowired
private RedisTemplate<String, String> redisTemplate;
// Problem: Can't test without Spring context, not final, harder to reason about

// ✅ Constructor Injection (Aries uses this)
public RateLimiterService(RedisTemplate<String, String> redisTemplate, ...) {
    this.redisTemplate = redisTemplate;
}
// Benefits: final fields, testable, explicit dependencies, immutable
```

### `RedisTemplate<String, String>` vs `StringRedisTemplate`

```java
// StringRedisTemplate is a pre-configured variant where both key and value serializers are StringRedisSerializer
// RedisTemplate<String, String> gives explicit generic typing needed for execute() with Lua scripts

// For Lua script execution, RedisTemplate.execute() returns the generic type T
// StringRedisTemplate.execute() would return String (wrong for our Long result type)
RedisTemplate<String, String> → execute() returns Long ✅
StringRedisTemplate           → execute() has different return handling ❌
```

### `MeterRegistry` Auto-Wired

When `micrometer-registry-prometheus` is on the classpath, Spring Boot auto-configures a `PrometheusMeterRegistry` bean implementing `MeterRegistry`. It's injected into `RateLimiterService` automatically.

### Counter vs Timer vs Gauge (Micrometer Types)

| Type | Use Case | Aries Example |
|---|---|---|
| `Counter` | Monotonically increasing count | `rate_limiter.allowed`, `.blocked` |
| `Timer` | Duration/count of events | HTTP request duration |
| `Gauge` | Point-in-time value | JVM heap size |
| `DistributionSummary` | Distribution of values | Request payload sizes |

---

## 5. `GrpcRateLimiterService.java` — gRPC Server Implementation

```java
package com.aries.service;

import com.aries.proto.RateLimitRequest;
import com.aries.proto.RateLimitResponse;
import com.aries.proto.RateLimiterServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class GrpcRateLimiterService extends RateLimiterServiceGrpc.RateLimiterServiceImplBase {

    private final RateLimiterService rateLimiterService;

    public GrpcRateLimiterService(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public void checkLimit(
            RateLimitRequest request,
            StreamObserver<RateLimitResponse> responseObserver) {

        String clientId = request.getClientId();           // Protobuf getter

        boolean allowed = rateLimiterService.isAllowed(clientId);

        RateLimitResponse response = RateLimitResponse.newBuilder()
                .setIsAllowed(allowed)                     // Protobuf builder pattern
                .build();

        responseObserver.onNext(response);   // Send response
        responseObserver.onCompleted();      // Signal end of stream
    }
}
```

### `@GrpcService` Annotation

Provided by `net.devh.boot.grpc-spring-boot-starter`. When Spring detects this annotation:
1. Registers the bean as a gRPC service implementation
2. The underlying Netty gRPC server (auto-started on port 9090) discovers it
3. Routes incoming gRPC calls matching the service name to this class

### `RateLimiterServiceGrpc.RateLimiterServiceImplBase`

This is **generated code** from `rate_limiter.proto`. It provides:
- The service definition name: `"aries.RateLimiterService"`
- Abstract method stubs `checkLimit()` that we must override
- Default implementations that return `UNIMPLEMENTED` gRPC status

### `StreamObserver<RateLimitResponse>`

For **unary RPC** (one request, one response), this is the callback mechanism:

```java
responseObserver.onNext(response);    // Send one response message
responseObserver.onCompleted();       // Signal: no more responses coming
// OR in error case:
responseObserver.onError(new StatusRuntimeException(Status.INTERNAL));
```

For **server streaming**, you'd call `onNext()` multiple times then `onCompleted()`.
For **client streaming**, you'd use the return value `StreamObserver<Request>`.

### Protobuf Builder Pattern

```java
// Generated code uses the Builder pattern (immutable objects)
RateLimitResponse.newBuilder()
    .setIsAllowed(allowed)  // Sets the 'is_allowed' field
    .build();               // Creates an immutable RateLimitResponse instance

// Field access uses generated getters
String clientId = request.getClientId();  // proto field: string client_id = 1;
// Proto3 naming: client_id in .proto → getClientId() in Java
```

### gRPC Call Lifecycle

```
1. Client creates ManagedChannel(host, 9090)
2. Client creates Blocking/Async stub from channel
3. Client calls stub.checkLimit(request)
4. gRPC framework serializes request → Protobuf binary
5. Sends over HTTP/2 to Aries gRPC server port 9090
6. Netty receives, deserializes → RateLimitRequest object
7. Routes to GrpcRateLimiterService.checkLimit()
8. Our code executes, calls rateLimiterService.isAllowed()
9. Redis Lua script executes
10. Response built, responseObserver.onNext(response)
11. Serialized → Protobuf binary → sent back
12. Client deserializes → RateLimitResponse.getIsAllowed()
```

---

## 6. `AriesTestController.java` — REST Debug Endpoint

```java
@RestController
public class AriesTestController {

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterService rateLimiterService;

    public AriesTestController(StringRedisTemplate redisTemplate,
                               RateLimiterService rateLimiterService) {
        this.redisTemplate      = redisTemplate;
        this.rateLimiterService = rateLimiterService;
    }

    // Endpoint 1: Atomic Redis increment for testing connectivity
    @GetMapping("/increment")
    public Long increment() {
        return redisTemplate.opsForValue().increment("test_counter");
    }

    // Endpoint 2: HTTP-accessible rate limit check
    @GetMapping("/ratecheck")
    public ResponseEntity<String> rateCheck() {
        boolean check = rateLimiterService.isAllowed("user1");
        if (check) {
            return ResponseEntity.ok("Here is your Data");  // 200
        } else {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                 .body("Too many requests");  // 429
        }
    }
}
```

### `@RestController` = `@Controller` + `@ResponseBody`

- `@Controller`: Marks as Spring MVC controller
- `@ResponseBody`: Return values are serialized directly to HTTP response body (JSON/String), not resolved as view names

### `/increment` — Redis INCR Command

```java
redisTemplate.opsForValue().increment("test_counter");
// Maps to: INCR test_counter
// Redis atomically increments by 1 and returns new value
// Use: verify Redis connection, test atomic increment behavior
```

### `/ratecheck` — HTTP Wrapper for Rate Limiting

This endpoint exists for **manual browser/curl testing** of the rate limit logic. The primary interface is gRPC, but this lets you:
- Test from a browser (no gRPC client needed)
- Test with `curl http://localhost:8000/ratecheck`
- Verify the 429 response behavior

### `ResponseEntity<String>`

Gives full control over HTTP response:
- Status code: `200 OK` or `429 TOO_MANY_REQUESTS`
- Body: `String` message
- Headers: (not set here, but possible)

---

## 7. `application.yml` — Configuration Deep Dive

```yaml
spring:
  application:
    name: aries-rate-limiter    # Service discovery name, appears in logs and metrics
  
  threads:
    virtual:
      enabled: true             # Enable Java 21 Virtual Threads for all request threads

  data:
    redis:
      host: ${REDIS_HOST:localhost}   # Env var with fallback default
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}    # Empty string default = no auth

server:
  port: ${HTTP_PORT:8000}       # HTTP port — gRPC port configured separately (default 9090)

management:
  endpoints:
    web:
      exposure:
        include: "*"            # Expose ALL actuator endpoints (health, metrics, prometheus, etc.)
  metrics:
    export:
      prometheus:
        enabled: true           # Enable Prometheus metrics export

logging:
  level:
    com.aries: DEBUG            # Debug-level logging for our package only
```

### Environment Variable Interpolation Pattern

`${VARIABLE_NAME:default_value}` is Spring's **placeholder syntax**:
- Checks environment variables first
- Falls back to default value if not set

This is **12-factor app** compliant (config via environment).

### Why `management.endpoints.web.exposure.include: "*"`?

Exposes all Spring Boot Actuator endpoints:
- `/actuator/health` — Health check (used by load balancers, k8s)
- `/actuator/prometheus` — Prometheus metrics scrape endpoint
- `/actuator/metrics` — All metrics (human-readable JSON)
- `/actuator/info` — App info
- `/actuator/env` — Environment properties

**Security Note**: In production, restrict this to only `"health,prometheus"` and add authentication.

---

## 8. Design Patterns Used in Aries

| Pattern | Where | Description |
|---|---|---|
| **Service Layer** | `RateLimiterService` | Business logic separated from API layer |
| **Facade** | `RateLimiterService` | Hides Redis + Lua complexity behind simple `isAllowed()` |
| **Repository** | `RedisTemplate` | Data access abstraction (Redis as data store) |
| **Factory Method** | `RedisConfig.@Bean` | Creates and configures complex objects centrally |
| **Builder** | Protobuf `Response.newBuilder()` | Constructs immutable response objects |
| **Template Method** | `RateLimiterServiceImplBase` | Base class defines gRPC lifecycle, we override |
| **Strategy** | `TokenBucket` vs Lua script | Different algorithm implementations, same interface |
| **Dependency Injection** | All constructors | Inversion of Control via Spring IoC container |
| **Singleton** | All Spring beans | Default scope — one instance per ApplicationContext |
