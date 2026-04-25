<div align="center">

<h1>⚡ Aries</h1>

<p><strong>A production-grade, distributed Rate Limiter as a Service (RLaaS)</strong></p>

<p>
  <a href="https://www.java.com/en/"><img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"/></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring_Boot-3.5.13-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white" alt="Spring Boot"/></a>
  <a href="https://grpc.io/"><img src="https://img.shields.io/badge/gRPC-HTTP%2F2-244C5A?style=for-the-badge&logo=grpc&logoColor=white" alt="gRPC"/></a>
  <a href="https://redis.io/"><img src="https://img.shields.io/badge/Redis-Lua_Atomic-DC382D?style=for-the-badge&logo=redis&logoColor=white" alt="Redis"/></a>
  <a href="https://prometheus.io/"><img src="https://img.shields.io/badge/Prometheus-Metrics-E6522C?style=for-the-badge&logo=prometheus&logoColor=white" alt="Prometheus"/></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue?style=for-the-badge" alt="License"/></a>
</p>

<p><em>Stop every rogue client before they hit your services. Aries sits on the critical path and responds in microseconds.</em></p>

</div>

---

## Table of Contents

- [Overview](#overview)
- [Why Aries?](#why-aries)
- [Architecture](#architecture)
- [How It Works](#how-it-works)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Build & Run](#build--run)
  - [Connecting Prometheus](#connecting-prometheus)
- [API Reference](#api-reference)
  - [gRPC (Primary)](#grpc-primary)
  - [REST (Debug / Testing)](#rest-debug--testing)
- [Configuration](#configuration)
- [Observability](#observability)
- [Design Decisions](#design-decisions)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

**Aries** is a standalone microservice that provides centralized, distributed rate limiting over **gRPC**. Any service in your ecosystem can call Aries with a `client_id` and receive an immediate `allowed: true/false` decision — in microseconds.

Under the hood, Aries runs an **atomically-safe Token Bucket algorithm** implemented as a Lua script executed directly inside Redis, eliminating all race conditions that plague application-level counters. Every decision is emitted as a Prometheus metric, giving you real-time visibility into traffic shapes and abuse patterns.

```
Your Microservice  ──gRPC──►  Aries  ──Lua──►  Redis
                  ◄──bool──          ◄──Long──
```

---

## Why Aries?

Without centralized rate limiting, every API is vulnerable to:

| Threat | Impact |
|---|---|
| **DDoS / Flooding** | Server crash under malicious request volume |
| **Scraping / Abuse** | Data theft at zero cost to the attacker |
| **Thundering Herd** | Post-outage recovery killed by request storms |
| **Cost Overruns** | Unbounded calls to paid downstream APIs (AI, SMS, payments) |
| **Fairness Violations** | Power users consume shared resources at others' expense |

### Why NOT just implement it inside each service?

```
❌ Per-Service Counter (broken under horizontal scaling):
   ServiceA (instance 1) → its own counter → 3/5 requests used
   ServiceA (instance 2) → its own counter → 3/5 requests used
   → 6 requests allowed when only 5 should be!

✅ Aries (RLaaS pattern):
   ServiceA (instance 1) ──gRPC──► Aries ──► Redis (shared state)
   ServiceA (instance 2) ──gRPC──► Aries ──► Redis (shared state)
   → All instances see the exact same token count
```

This is the same pattern used by **Stripe**, **GitHub**, **Cloudflare**, and **AWS API Gateway**.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                      CLIENT MICROSERVICES                        │
│          (auth-service, payment-service, user-service, ...)      │
└────────────────────────┬─────────────────────────────────────────┘
                         │  gRPC  (HTTP/2 + Protobuf)  :9090
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│                     ARIES RATE LIMITER SERVICE                   │
│                                                                  │
│   ┌──────────────────────┐    ┌─────────────────────────────┐   │
│   │  GrpcRateLimiterSvc  │───►│      RateLimiterService      │   │
│   │  (@GrpcService)      │    │  (Business Logic + Metrics)  │   │
│   └──────────────────────┘    └──────────────┬──────────────┘   │
│                                              │  EVALSHA          │
│   ┌──────────────────────────────────────────▼──────────────┐   │
│   │              tokens_bucket.lua  (Atomic Script)          │   │
│   └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│   ┌────────────────┐  REST :8000                                 │
│   │ AriesTest      │  GET /ratecheck  POST /increment            │
│   │ Controller     │  (manual testing & debugging)              │
│   └────────────────┘                                            │
│                                                                  │
│   ┌────────────────┐                                            │
│   │ Spring Actuator│──► GET /actuator/prometheus                │
│   └────────────────┘                                            │
└──────────────────────────────────────────────────────────────────┘
                         │  HMGET / HMSET / EXPIRE
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│                            REDIS                                 │
│  key:  "rate_limit:user1"                                        │
│  hash: { tokens: 4,  last_refill: 1714000000000 }               │
└──────────────────────────────────────────────────────────────────┘
                         │
                         │  Prometheus scrapes /actuator/prometheus
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│                         PROMETHEUS                               │
│  aries_rate_limiter_allowed_total                                │
│  aries_rate_limiter_blocked_total                                │
└──────────────────────────────────────────────────────────────────┘
```

---

## How It Works

Aries implements a **Token Bucket** algorithm. Each `client_id` gets its own virtual bucket of tokens in Redis.

```
Bucket state per client:
  capacity    = 5     → maximum burst size
  refill_rate = 1     → tokens added per interval
  interval    = 1000ms

Timeline example (capacity=5, rate=1 token/second):
  T=0s   → 5 tokens (full)  → Request 1–5: ALLOWED ✅
  T=0s   → 0 tokens          → Request 6:   BLOCKED ❌  (HTTP 429)
  T=1s   → 1 token refilled  → Request 7:   ALLOWED ✅
```

The entire read-refill-consume-write cycle is executed as a **single atomic Lua script** inside Redis. Because Redis is single-threaded, no race condition is possible — not even under 10,000 concurrent callers.

```lua
-- tokens_bucket.lua (simplified)
local bucket      = redis.call('HMGET', key, 'tokens', 'last_refill')
local refills     = math.floor((now - last_refill) / refill_interval)
local tokens      = math.min(capacity, tokens + refills * refill_rate)

if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', ...)
    return {1}   -- ALLOWED
end

redis.call('HMSET', key, 'tokens', tokens, 'last_refill', ...)
return {0}       -- BLOCKED
```

---

## Tech Stack

| Component | Technology | Why |
|---|---|---|
| Language | **Java 21** | Virtual Threads (Project Loom) for million-scale concurrency |
| Framework | **Spring Boot 3.5.13** | Auto-config, Actuator, DI — production-ready from day one |
| API Protocol | **gRPC + Protobuf** | Binary serialization, HTTP/2 multiplexing — ~3-10x faster than REST |
| State Store | **Redis** | Sub-millisecond latency, single-threaded atomicity, built-in TTL |
| Atomicity | **Redis Lua Script** | Read-modify-write in one atomic operation — zero race conditions |
| Metrics | **Micrometer + Prometheus** | Vendor-neutral metric facade; pull-based scraping |
| Redis Client | **Lettuce** | Non-blocking, single-connection, Java 21 compatible |
| Build Tool | **Maven 3.x** | Standard lifecycle with Protobuf code generation plugin |

### Why Virtual Threads Matter Here

A rate limiter is a **high-throughput, IO-bound** service. Every call blocks on Redis:

```
Without Virtual Threads: 10,000 concurrent requests = 10,000 blocked OS threads (~10 GB RAM)
With    Virtual Threads: 10,000 concurrent requests = 10,000 parked virtual threads (~10 MB)
```

One line of config enables it for all Spring components:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

---

## Project Structure

```
Aries/
├── Aries/                              # Maven module
│   ├── src/
│   │   └── main/
│   │       ├── java/com/aries/
│   │       │   ├── AriesApplication.java           # Entry point
│   │       │   ├── algorithms/
│   │       │   │   └── TokenBucket.java             # Reference implementation (educational)
│   │       │   ├── config/
│   │       │   │   └── RedisConfig.java             # Lua script bean registration
│   │       │   ├── controller/
│   │       │   │   └── AriesTestController.java     # REST debug endpoints
│   │       │   └── service/
│   │       │       ├── GrpcRateLimiterService.java  # gRPC endpoint (@GrpcService)
│   │       │       └── RateLimiterService.java      # Core business logic + metrics
│   │       ├── proto/
│   │       │   └── rate_limiter.proto               # gRPC service contract
│   │       └── resources/
│   │           ├── application.yml                  # App configuration
│   │           └── tokens_bucket.lua                # Atomic Token Bucket script
│   ├── prometheus.yml                               # Prometheus scrape config
│   └── pom.xml
├── Docs/                               # In-depth technical documentation
│   ├── 00_QUICK_REFERENCE.md
│   ├── 01_PROJECT_OVERVIEW.md
│   ├── 02_TECHNOLOGY_STACK.md
│   ├── 03_RATE_LIMITING_ALGORITHMS.md
│   ├── 04_SYSTEM_ARCHITECTURE_AND_CODE.md
│   ├── 05_GRPC_DEEP_DIVE.md
│   ├── 06_REDIS_DEEP_DIVE.md
│   ├── 07_OBSERVABILITY_METRICS.md
│   ├── 08_TESTING_STRATEGY.md
│   ├── 09_BUILD_SYSTEM_MAVEN.md
│   ├── 10_INTERVIEW_QA.md
│   └── 11_SYSTEM_DESIGN_PRODUCTION.md
└── LICENSE
```

---

## Getting Started

### Prerequisites

| Requirement | Version |
|---|---|
| JDK | 21+ |
| Maven | 3.8+ |
| Redis | 6+ (running locally or via Docker) |
| Docker | Any recent version (for Prometheus) |

### Build & Run

**1. Start Redis**

```bash
# Using Docker (recommended)
docker run -d --name redis -p 6379:6379 redis:latest

# Or with a local Redis installation
redis-server
```

**2. Clone and build**

```bash
git clone https://github.com/your-username/Aries.git
cd Aries/Aries
mvn clean install
```

> **Note:** The first build will download `protoc` and the gRPC Java plugin for your OS automatically via the `protobuf-maven-plugin`. An internet connection is required.

**3. Run**

```bash
mvn spring-boot:run
```

Or run the generated JAR directly:

```bash
java -jar target/Aries-0.0.1-SNAPSHOT.jar
```

Aries starts two servers:
- **gRPC server** → `localhost:9090`
- **HTTP server** → `localhost:8000`

**4. Smoke-test the REST endpoint**

```bash
curl http://localhost:8000/ratecheck
# → "Here is your Data"    (HTTP 200 — allowed)

# Fire 6 rapid requests to trigger the limit
for i in {1..6}; do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/ratecheck; done
# → 200 200 200 200 200 429
```

### Connecting Prometheus

**1. Start Prometheus with the bundled config**

```bash
cd Aries/Aries
docker run -d \
  --name prometheus \
  -p 9090:9090 \
  -v "$(pwd)/prometheus.yml:/etc/prometheus/prometheus.yml" \
  prom/prometheus
```

**2. Open the Prometheus UI**

Navigate to `http://localhost:9090` and query:

```promql
rate(rate_limiter_allowed_total[1m])
rate(rate_limiter_blocked_total[1m])
```

---

## API Reference

### gRPC (Primary)

**Service definition** (`src/main/proto/rate_limiter.proto`):

```proto
syntax = "proto3";
package aries;

option java_package = "com.aries.proto";
option java_multiple_files = true;

service RateLimiterService {
  rpc CheckLimit (RateLimitRequest) returns (RateLimitResponse);
}

message RateLimitRequest {
  string client_id = 1;
}

message RateLimitResponse {
  bool is_allowed = 1;
}
```

**Calling from a Java client:**

```java
ManagedChannel channel = ManagedChannelBuilder
    .forAddress("localhost", 9090)
    .usePlaintext()
    .build();

RateLimiterServiceGrpc.RateLimiterServiceBlockingStub stub =
    RateLimiterServiceGrpc.newBlockingStub(channel);

RateLimitRequest request = RateLimitRequest.newBuilder()
    .setClientId("user-42")
    .build();

RateLimitResponse response = stub.checkLimit(request);
// response.getIsAllowed() → true / false
```

**Calling from any other language** — generate a client from the `.proto` file using `protoc`. Aries is fully polyglot.

### REST (Debug / Testing)

| Method | Endpoint | Description | Response |
|---|---|---|---|
| `GET` | `/ratecheck` | Check rate limit for `user1` (hardcoded for testing) | `200 OK` or `429 Too Many Requests` |
| `POST` | `/increment` | Manually trigger a rate-limit check | `200 OK` or `429 Too Many Requests` |
| `GET` | `/actuator/health` | Service health check | `{"status":"UP"}` |
| `GET` | `/actuator/prometheus` | Prometheus metrics scrape endpoint | Prometheus text format |

---

## Configuration

Edit `Aries/src/main/resources/application.yml`:

```yaml
server:
  port: 8000           # REST / Actuator port

grpc:
  server:
    port: 9090         # gRPC server port

spring:
  data:
    redis:
      host: localhost
      port: 6379
  threads:
    virtual:
      enabled: true    # Java 21 Virtual Threads

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true
```

**Rate limit parameters** (currently in `RateLimiterService.java`):

```java
redisTemplate.execute(
    tokenBucketScript,
    List.of("rate_limit:" + userId),
    "5",     // capacity      — max burst tokens
    "1",     // refill_rate   — tokens per interval
    "1000",  // interval (ms) — 1 second
    String.valueOf(System.currentTimeMillis())
);
```

> **Roadmap:** These values will be externalized to `@ConfigurationProperties` to support per-client, per-tier rate limit policies without code changes.

---

## Observability

Aries emits the following metrics to Prometheus via `/actuator/prometheus`:

| Metric | Type | Description |
|---|---|---|
| `rate_limiter_allowed_total` | Counter | Total requests that passed the rate limit |
| `rate_limiter_blocked_total` | Counter | Total requests that were rejected (429) |
| `jvm_memory_used_bytes` | Gauge | JVM heap memory consumption |
| `process_cpu_usage` | Gauge | CPU usage of the service process |
| `grpc_server_calls_total` | Counter | Total gRPC calls received |
| `http_server_requests_seconds` | Timer | Latency distribution of REST endpoints |

**Example PromQL queries:**

```promql
# Rejection rate over the last 5 minutes
rate(rate_limiter_blocked_total[5m])

# Allowed vs blocked ratio
rate_limiter_allowed_total / (rate_limiter_allowed_total + rate_limiter_blocked_total)

# 99th percentile gRPC latency
histogram_quantile(0.99, grpc_server_calls_seconds_bucket)
```

---

## Design Decisions

<details>
<summary><strong>Why gRPC instead of REST?</strong></summary>

Rate limiting is on the **critical path of every single request**. Every microsecond matters.

| | REST/HTTP 1.1 | gRPC/HTTP 2 |
|---|---|---|
| Serialization | JSON (~350 bytes) | Protobuf (~13 bytes) |
| Connections | New TCP per request | Persistent multiplexed stream |
| Type safety | Optional (OpenAPI) | Enforced by `.proto` contract |
| Code generation | Manual / Swagger | Automatic from `.proto` |

gRPC with binary Protobuf is **~3–10× faster** for this workload.
</details>

<details>
<summary><strong>Why a Redis Lua script instead of Java-level Redis calls?</strong></summary>

Without atomicity, concurrent callers can read the same token count and both consume the same token — violating the rate limit guarantee:

```
Thread A: GET tokens → "5"
Thread B: GET tokens → "5"   ← reads BEFORE A writes back
Thread A: SET tokens → "4"
Thread B: SET tokens → "4"   ← both consumed 1 token but both got through!
```

A Lua script runs entirely inside Redis's single-threaded engine. No lock, no transaction, no race condition — ever.
</details>

<details>
<summary><strong>Why Token Bucket and not Fixed Window or Sliding Log?</strong></summary>

| Property | Token Bucket | Fixed Window | Sliding Log |
|---|---|---|---|
| Allows natural bursts | ✅ | ✅ | ❌ |
| No boundary spike | ✅ | ❌ | ✅ |
| Memory per user | O(1) | O(1) | O(requests) |
| Computation | O(1) | O(1) | O(log n) |
| Millisecond precision | ✅ | ❌ | ✅ |

Token Bucket is the industry standard for API rate limiting (Stripe, GitHub, AWS) because it handles bursty real-world traffic gracefully while enforcing a smooth average rate.
</details>

<details>
<summary><strong>Why Java 21 Virtual Threads instead of reactive/WebFlux?</strong></summary>

Virtual Threads (Project Loom) provide identical throughput to reactive programming for IO-bound workloads — without the complexity of `Mono<>`, `Flux<>`, and callback chains. The code stays imperative, readable, and debuggable. For Java 21+, this is the recommended approach from the Spring team.
</details>

---

## Roadmap

- [ ] Externalize rate limit parameters to `@ConfigurationProperties` for per-client policies
- [ ] Add `Retry-After` header to all `429` responses
- [ ] Grafana dashboard with pre-built panels for allowed/blocked rates
- [ ] Docker Compose file for one-command local setup (Aries + Redis + Prometheus + Grafana)
- [ ] Support for multiple algorithms (Sliding Window, Fixed Window) via strategy pattern
- [ ] Per-endpoint rate limit rules via a configuration YAML
- [ ] gRPC health check protocol (`grpc.health.v1`)
- [ ] Distributed tracing with OpenTelemetry

---

## Contributing

Contributions are welcome! Please open an issue first to discuss what you'd like to change.

1. Fork the repository
2. Create your feature branch: `git checkout -b feat/your-feature`
3. Commit your changes: `git commit -m 'feat: add your feature'`
4. Push to the branch: `git push origin feat/your-feature`
5. Open a Pull Request

Please ensure your code follows the existing style and all tests pass (`mvn test`).

---

## License

Distributed under the **Apache License 2.0**. See [`LICENSE`](./LICENSE) for full terms.

---

<div align="center">
  <p>Built with ☕ and a deep respect for distributed systems.</p>
</div>
