# 📋 Quick Reference & Cheat Sheet

> Print this or have it open 30 minutes before your interview. Fast recall of key facts.

---

## 🎯 The One-Line Summary

> **"Aries is a distributed Rate Limiter as a Service using Spring Boot 3, gRPC for low-latency API, Redis Lua scripting for atomic Token Bucket algorithm, and Micrometer+Prometheus for observability."**

---

## 📁 File Map (What Does What)

| File | Layer | Purpose |
|---|---|---|
| `AriesApplication.java` | Bootstrap | Spring Boot entry point |
| `rate_limiter.proto` | Contract | gRPC service definition |
| `GrpcRateLimiterService.java` | API Layer | gRPC server — receives calls |
| `RateLimiterService.java` | Business Logic | Executes Lua, increments metrics |
| `RedisConfig.java` | Config | Loads Lua script as Spring Bean |
| `tokens_bucket.lua` | Algorithm | Atomic Token Bucket in Redis |
| `TokenBucket.java` | Algorithm (edu) | Java Token Bucket (race condition demo) |
| `AriesTestController.java` | Debug API | REST endpoints for manual testing |
| `application.yml` | Config | All app/Redis/gRPC/metrics settings |
| `prometheus.yml` | Config | Prometheus scrape configuration |
| `GrpcStressTest.java` | Test | 100 concurrent gRPC calls stress test |

---

## 🔢 Numbers to Know

| Parameter | Value | Meaning |
|---|---|---|
| Token capacity | `5` | Max burst of 5 requests |
| Refill rate | `1` | 1 token per interval |
| Refill interval | `1000ms` (1 second) | 1 req/sec sustained |
| TTL | `60 seconds` | Keys expire after 60s inactivity |
| HTTP port | `8000` | REST + Actuator port |
| gRPC port | `9090` | Primary API port |
| Redis port | `6379` | Default Redis port |
| Prometheus scrape interval | `5s` | Every 5 seconds |
| Stress test threads | `50` | Concurrent gRPC clients |
| Stress test requests | `100` | Total requests submitted |

---

## ⚙️ Technology Stack Card

| Technology | Version | Role |
|---|---|---|
| Java | 21 (LTS) | Language + Virtual Threads |
| Spring Boot | 3.5.13 | Framework |
| Maven | 3.x | Build tool |
| gRPC (net.devh) | 3.1.0 | gRPC Spring integration |
| Protobuf | 3.25.3 | Serialization format |
| protoc-gen-grpc-java | 1.62.2 | gRPC code generator |
| Lettuce | (managed by Boot) | Redis client (non-blocking) |
| Redis | 7.x | Shared state store |
| Lua | (built into Redis) | Atomic scripting |
| Micrometer | (managed) | Metrics facade |
| Prometheus | (Docker) | Metrics storage |
| Actuator | (managed) | Management endpoints |
| Lombok | (managed) | Boilerplate reduction |

---

## 🔑 Key Annotations Quick Reference

| Annotation | From | What It Does |
|---|---|---|
| `@SpringBootApplication` | Spring Boot | Entry point, enables auto-config + scan |
| `@Configuration` | Spring | Class contains `@Bean` definitions |
| `@Bean` | Spring | Method return = Spring singleton bean |
| `@Service` | Spring | Business logic layer bean |
| `@RestController` | Spring MVC | HTTP controller, returns JSON/String |
| `@GetMapping("/path")` | Spring MVC | Maps GET requests to method |
| `@GrpcService` | net.devh | Registers class as gRPC server service |

---

## 📜 Lua Script Summary

```
INPUTS:  KEYS[1]=rate_limit:{userId}, ARGV[1]=capacity, ARGV[2]=rate, 
         ARGV[3]=interval_ms, ARGV[4]=now_ms

STEP 1:  HMGET key tokens last_refill        (read current state)
STEP 2:  if nil → initialize with full bucket
STEP 3:  time_passed = now - last_refill
         refills = floor(time_passed / interval)
         tokens = min(capacity, tokens + refills)
         last_refill += refills * interval  (preserve remainder!)
STEP 4:  if tokens >= 1: tokens--, allowed=1, else allowed=0
STEP 5:  HMSET key tokens last_refill        (write new state)
         EXPIRE key 60                       (reset TTL)
OUTPUT:  {allowed}   (1=allowed, 0=blocked)
```

---

## 🌐 API Contract

### gRPC (PRIMARY)
```
Service:  aries.RateLimiterService
Method:   CheckLimit (Unary)
Request:  { client_id: "user1" }
Response: { is_allowed: true/false }
Port:     9090
Protocol: HTTP/2 + Protobuf binary
```

### REST (DEBUG)
```
GET /ratecheck      → 200 "Here is your Data" OR 429 "Too many requests"
GET /increment      → Counter value (tests Redis INCR)
GET /actuator/health → {"status":"UP"}
GET /actuator/prometheus → Prometheus metrics text
```

---

## 💡 Key Concepts to Explain Fluently

### Why Lua? (Atomicity)
> Redis is single-threaded. Lua script = ONE atomic command. No interleaving between reads and writes. Prevents race conditions in distributed systems.

### Why gRPC over REST?
> Binary Protobuf, HTTP/2 multiplexing, type-safe, ~3-10x smaller, code gen from .proto. Perfect for internal service communication.

### Why Virtual Threads?
> IO-bound service (blocks on Redis). Virtual threads = ~1KB vs 1MB platform threads. Millions of concurrent waiting requests without OOM.

### Why Redis TTL?
> Inactive users accumulate keys forever → memory leak. EXPIRE 60 auto-cleans after inactivity. Returning users get fresh full bucket.

### Why Token Bucket?
> O(1) memory, O(1) compute, allows bursts (good UX), smooth long-term average, configurable. vs Sliding Window Log (O(n) memory, no bursts).

### Race Condition in TokenBucket.java?
> 4 separate Redis commands. Between GET and SET, another thread reads stale value. Both threads decrement from same token count. Lua atomicity prevents this.

---

## 🔥 Power Phrases for Interviews

- *"I deliberately chose atomic Lua scripting over Redis transactions because MULTI/EXEC cannot execute conditional logic — you can't check tokens and conditionally decrement in a single transaction, but you CAN in a Lua script."*

- *"The critical insight is that Redis's single-threaded execution model makes Lua scripts the synchronization primitive for distributed systems — no locks, no deadlocks, just atomic operations."*

- *"Virtual threads are the reason I can handle high concurrency without reactive programming — they provide the same IO efficiency as Mono/Flux but with imperative, debuggable, readable code."*

- *"The key naming strategy `rate_limit:{userId}` is deliberate — it groups all rate limit keys with a namespace prefix, making them discoverable, debuggable, and ready for Redis Cluster with hash tag grouping."*

- *"I built two implementations intentionally — the Java TokenBucket to demonstrate the algorithm and its race condition, and the Lua script as the production-grade fix. This shows I understand both the problem and the solution."*

---

## ⚠️ Bugs Fixed (Know These Cold)

| Bug | Root Cause | Fix |
|---|---|---|
| `ClassCastException` | `StringRedisTemplate.get()` returns `String`, code cast to `Integer` | Use `Integer.parseInt()` |
| Docker mount error | `promethus.yml` (typo) vs `prometheus.yml` (correct) | Rename file correctly |
| Java 17 compilation | Maven compiler not explicitly configured for Java 21 | Set `<java.version>21</java.version>` |
| Protobuf compilation error | Naming conflict between outer class and message | Add `option java_multiple_files = true` |

---

## 🎓 Resume Bullet Points

```
• Built Aries, a distributed Rate Limiter as a Service (RLaaS) using Spring Boot 3 and gRPC,
  exposing a Protobuf-based API over HTTP/2 for type-safe, low-latency rate limit checks

• Implemented the Token Bucket algorithm using Redis Lua scripting for fully atomic,
  race-condition-free rate enforcement across distributed service instances

• Reduced Redis round-trips by 50% by migrating from string-key to hash-field storage
  model (HMGET/HMSET), with per-key TTL for automatic memory cleanup

• Integrated Micrometer + Prometheus for real-time observability of allowed/blocked request
  ratios, enabling live dashboards and alerting

• Leveraged Java 21 Virtual Threads to handle high-concurrency IO-bound Redis operations
  without platform thread exhaustion
```
