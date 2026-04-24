# ⚙️ Technology Stack — Deep Dive

> Every technology here was chosen deliberately. This doc explains **what** each technology is, **why** it was chosen, and **how** it fits into Aries. Be ready to defend each choice.

---

## 1. Spring Boot 3.5.13

### What It Is
Spring Boot is an **opinionated convention-over-configuration** framework built on top of the Spring Framework. It auto-configures beans, manages dependencies, and provides embedded servers.

### Why Spring Boot for Aries?
- **Auto-configuration**: Redis, Actuator, Prometheus, gRPC — all work with zero XML
- **Dependency Injection (IoC container)**: `RateLimiterService` gets `RedisTemplate` and `MeterRegistry` injected automatically
- **Spring Boot Actuator**: Gives `/actuator/health`, `/actuator/prometheus`, `/actuator/metrics` for FREE — no code needed
- **Production-ready**: Health checks, graceful shutdown, externalized config via environment variables

### Spring Boot vs Plain Spring

```java
// Plain Spring — 50 lines of XML configuration
// Spring Boot — just this:
@SpringBootApplication
public class AriesApplication {
    public static void main(String[] args) {
        SpringApplication.run(AriesApplication.class, args);
    }
}
```

### Key Interview Points
- `@SpringBootApplication` = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`
- Auto-configuration checks the classpath (e.g., if `spring-boot-starter-data-redis` is present, it auto-configures `RedisTemplate`)
- `spring-boot-starter-parent` manages all dependency versions (BOM — Bill of Materials)

---

## 2. Java 21 + Virtual Threads (Project Loom)

### What It Is
Java 21 is an **LTS (Long Term Support)** release introducing Virtual Threads via **Project Loom** — a JVM-level feature that decouples threads from OS threads.

### Platform Threads vs Virtual Threads

| | Platform Thread | Virtual Thread |
|---|---|---|
| Backed by | OS thread (1:1) | JVM scheduler (M:N) |
| Memory | ~1MB stack | ~1KB |
| Creation cost | Expensive (syscall) | Cheap (~μs) |
| Max practical count | ~10,000 | ~millions |
| Blocking behavior | Blocks OS thread | Parks (yields JVM) |

### How Aries Uses It

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

This single config makes Spring Boot use virtual threads for:
- All Tomcat HTTP request threads
- All gRPC stub execution threads
- All `@Async` tasks

### Why This Matters for a Rate Limiter
A rate limiter is a **high-throughput, IO-bound** service. Every request:
1. Reads from Redis (network IO — blocking)
2. Executes Lua script (network IO — blocking)
3. Writes back to Redis (network IO — blocking)

With platform threads, 10,000 concurrent requests = 10,000 blocked OS threads = possible OOM.
With virtual threads, 10,000 concurrent requests = 10,000 parked virtual threads = ~10MB total.

### Interview Question: "Why not use reactive/WebFlux?"
> "Virtual threads give us the same throughput benefits as reactive programming but without the cognitive overhead of `Mono<>`, `Flux<>`, and callback chains. The code stays imperative and readable. For Java 21+, virtual threads are the recommended approach for IO-bound services."

---

## 3. gRPC + Protocol Buffers

### What Is gRPC?
gRPC is Google's **Remote Procedure Call framework** built on:
- **HTTP/2** as transport (multiplexed, compressed, persistent connections)
- **Protocol Buffers (Protobuf)** as serialization format (binary, typed, compact)

### What Is Protobuf?
A language-neutral, platform-neutral, binary serialization format. You define your data contract in a `.proto` file, and `protoc` (the Protobuf compiler) generates type-safe code in Java (or any target language).

### Aries's `.proto` Contract

```proto
// src/main/proto/rate_limiter.proto
syntax = "proto3";
package aries;

option java_package = "com.aries.proto";
option java_multiple_files = true;  // Each message = separate .java file

service RateLimiterService {
  rpc CheckLimit (RateLimitRequest) returns (RateLimitResponse);
}

message RateLimitRequest {
  string client_id = 1;  // field number 1 = tag in binary encoding
}

message RateLimitResponse {
  bool is_allowed = 1;
}
```

### What `protoc` Generates (at compile time via Maven plugin)
```
target/generated-sources/protobuf/
├── grpc-java/
│   └── com/aries/proto/RateLimiterServiceGrpc.java  ← Stub classes
└── java/
    ├── com/aries/proto/RateLimitRequest.java         ← Request POJO
    ├── com/aries/proto/RateLimitResponse.java        ← Response POJO
    └── com/aries/proto/RateLimiterServiceOuterClass.java
```

### gRPC vs REST — The Technical Comparison

```
REST/HTTP1.1:
  POST /rate-check HTTP/1.1
  Content-Type: application/json
  {"client_id": "user1"}           ← ~30 bytes
  
  HTTP/1.1 200 OK
  {"is_allowed": true}             ← ~20 bytes
  
  Total: ~50 bytes + HTTP headers (~300 bytes) = ~350 bytes
  New TCP connection per request (HTTP/1.1 without keep-alive)

gRPC/HTTP2/Protobuf:
  Binary frame: [field_tag=1][length][u][s][e][r][1]  ← ~10 bytes
  Binary response: [field_tag=1][0x01]                 ← ~3 bytes
  
  Total: ~13 bytes on existing multiplexed HTTP/2 stream
  1 TCP connection, many concurrent streams
```

### The `net.devh.boot.grpc` Starter

This Spring Boot starter auto-configures a **Netty-based gRPC server** on port `9090`. Any class annotated with `@GrpcService` is automatically registered as a gRPC endpoint.

```java
// No server setup code needed — just annotate the implementation!
@GrpcService
public class GrpcRateLimiterService extends RateLimiterServiceGrpc.RateLimiterServiceImplBase {
    // ...
}
```

### Maven Plugin Configuration (Critical Interview Topic)

```xml
<!-- pom.xml -->
<extension>
    <groupId>kr.motd.maven</groupId>
    <artifactId>os-maven-plugin</artifactId>
    <version>1.7.0</version>
</extension>
```
This detects the OS (Windows/Linux/Mac) and provides `${os.detected.classifier}` (e.g., `windows-x86_64`).

```xml
<plugin>
    <groupId>org.xolstice.maven.plugins</groupId>
    <artifactId>protobuf-maven-plugin</artifactId>
    <version>0.6.1</version>
    <configuration>
        <!-- Downloads the protoc compiler binary for current OS -->
        <protocArtifact>com.google.protobuf:protoc:3.25.3:exe:${os.detected.classifier}</protocArtifact>
        <!-- Downloads the gRPC Java code generator plugin -->
        <pluginArtifact>io.grpc:protoc-gen-grpc-java:1.62.2:exe:${os.detected.classifier}</pluginArtifact>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>compile</goal>         <!-- Generates message classes -->
                <goal>compile-custom</goal>  <!-- Generates gRPC stub classes -->
            </goals>
        </execution>
    </executions>
</plugin>
```

**Build sequence**: `mvn clean install` → `os-maven-plugin` detects OS → `protobuf-maven-plugin` downloads `protoc` binary → runs `protoc` on `.proto` files → generates Java classes → Java compiler compiles everything together.

---

## 4. Redis

### What It Is
Redis (Remote Dictionary Server) is an **in-memory data structure store** that supports strings, hashes, lists, sets, sorted sets, streams, and more. It is single-threaded for command execution, making operations serializable.

### Why Redis for Rate Limiting?
1. **Sub-millisecond latency** — data is in RAM, not disk
2. **Atomic single-threaded engine** — no race conditions for counter operations
3. **Built-in TTL** — `EXPIRE` auto-cleans stale keys
4. **Data structure richness** — Hash type perfectly maps to `{tokens, last_refill}` state

### How Aries Uses Redis

```
Key: "rate_limit:user1"
Type: Hash
Fields:
  tokens      → "4"           (current token count)
  last_refill → "1714000000" (Unix timestamp ms of last refill)
```

### `spring-boot-starter-data-redis`

Auto-configures:
- `RedisConnectionFactory` (using Lettuce, a non-blocking Redis client)
- `RedisTemplate<K, V>` — general purpose template
- `StringRedisTemplate` — specialized for String keys/values

### `RedisTemplate` vs `StringRedisTemplate`

```java
// StringRedisTemplate — specialized for String keys AND values
StringRedisTemplate stringRedis;
stringRedis.opsForValue().get("key"); // returns String

// RedisTemplate<String, String> — explicitly typed generic
RedisTemplate<String, String> redisTemplate;
redisTemplate.execute(script, keys, args); // used for Lua scripts, returns Long
```

**Critical Bug Aries Fixed**: The `TokenBucket.java` class initially used `StringRedisTemplate` and tried to cast its return to `Integer`/`Long`. Since `StringRedisTemplate` always returns `String`, this caused `ClassCastException`. The fix was `Integer.parseInt(tokenStr)` and `Long.parseLong(timeStr)`.

### Lettuce vs Jedis

| | Lettuce (default) | Jedis |
|---|---|---|
| Threading | Non-blocking, single connection | Blocking, connection pool |
| Reactive | Yes | No |
| Java 21 | Better fit | OK |
| Cluster support | Yes | Yes |

Aries uses Lettuce (Spring Boot default).

---

## 5. Redis Lua Scripting

### Why Lua in Redis?
Redis executes Lua scripts **atomically** — the entire script runs as a single unit without interruption. This is the **only correct way** to implement read-modify-write operations in Redis without transactions.

### How `EVALSHA` Works

```bash
# First call: Redis compiles and caches the script
EVALSHA <sha1> numkeys key1 key2 arg1 arg2 ...

# Script can call Redis commands
redis.call('GET', key)
redis.call('HMSET', key, 'tokens', 4)
```

### Spring's `DefaultRedisScript`

```java
// RedisConfig.java
@Bean
public RedisScript<Long> tokenBucketScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("tokens_bucket.lua")); // loads from resources/
    script.setResultType(Long.class);  // Lua returns table [1] or [0], Spring maps first element
    return script;
}
```

Spring:
1. Loads the Lua file from classpath on startup
2. Calculates SHA1 hash
3. First call: `SCRIPT LOAD` the script → Redis stores it by SHA1
4. Subsequent calls: `EVALSHA sha1 keys args` (faster — avoids sending the full script each time)

---

## 6. Micrometer + Prometheus

### What Is Micrometer?
Micrometer is a **metrics facade** for JVM applications. It's the "SLF4J for metrics" — you write vendor-neutral metric code, and it exports to Prometheus, Datadog, CloudWatch, etc.

### What Is Prometheus?
Prometheus is a **pull-based time-series monitoring system**. It scrapes `/actuator/prometheus` every N seconds and stores metrics with timestamps.

### How Aries Instruments Metrics

```java
// RateLimiterService.java
this.allowedCounter = meterRegistry.counter("rate_limiter.allowed");
this.blockedCounter = meterRegistry.counter("rate_limiter.blocked");

// After every decision:
if (allowed) {
    allowedCounter.increment();  // rate_limiter_allowed_total++
} else {
    blockedCounter.increment();  // rate_limiter_blocked_total++
}
```

### Prometheus Pull Model

```yaml
# prometheus.yml
global:
  scrape_interval: 5s            # Pull every 5 seconds

scrape_configs:
  - job_name: 'aries'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8000']  # Aries runs on host, Prometheus in Docker
```

`host.docker.internal` is Docker's magic hostname that resolves to the host machine's IP from inside a container.

### Key Metrics Exposed

| Metric | Type | Description |
|---|---|---|
| `rate_limiter_allowed_total` | Counter | Total requests allowed |
| `rate_limiter_blocked_total` | Counter | Total requests blocked |
| `jvm_memory_used_bytes` | Gauge | JVM heap memory |
| `process_cpu_usage` | Gauge | CPU usage |
| `grpc_server_calls_total` | Counter | Total gRPC calls |
| `redis_command_latency` | Timer | Redis command duration |
| `http_server_requests_seconds` | Timer | REST endpoint latency |

---

## 7. Lombok

### What It Is
Lombok is a Java annotation processor that generates boilerplate code (getters, setters, constructors, builders) at compile time.

### Aries Usage
In Aries, Lombok is a **declared optional dependency** but not heavily used (constructor injection is done manually). It's included as a standard production practice.

```xml
<!-- pom.xml — excluded from final JAR -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<!-- Also configured as annotation processor for compile-time processing -->
<annotationProcessorPaths>
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
    </path>
</annotationProcessorPaths>
```

`optional: true` + excluded from spring-boot-maven-plugin = **NOT bundled in the final JAR** (compile-time only tool).

---

## 8. Technology Decision Matrix

| Technology | Category | Why Chosen | Alternative |
|---|---|---|---|
| Spring Boot 3 | Framework | Auto-config, ecosystem, production-ready | Quarkus, Micronaut |
| Java 21 | Language | Virtual threads, LTS, modern features | Kotlin, Java 17 |
| gRPC | API Protocol | Binary, fast, type-safe, polyglot | REST HTTP, GraphQL |
| Protobuf | Serialization | Compact binary, typed schema | JSON, Avro, Thrift |
| Redis | State Store | Sub-ms latency, atomic Lua, TTL | Memcached, Hazelcast |
| Lua Script | Atomicity | Redis-native, no transactions needed | Redis MULTI/EXEC |
| Micrometer | Metrics | Vendor-neutral facade | Direct Prometheus SDK |
| Prometheus | Monitoring | Pull-model, powerful PromQL | Datadog, CloudWatch |
| Lettuce | Redis Client | Non-blocking, single connection | Jedis |
| Maven | Build | Standard Java build tool | Gradle |
