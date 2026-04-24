# 📌 Aries — Project Overview & Core Concepts

> **Interview Mantra**: "Aries is a production-grade, distributed Rate Limiter as a Service (RLaaS) built on Spring Boot 3, exposing a gRPC API for low-latency inter-service communication, backed by a Redis-atomic Lua-scripted Token Bucket algorithm, and instrumented with Micrometer + Prometheus for observability."

---

## 1. What Is Aries?

Aries is a **standalone microservice** that acts as a **Rate Limiter as a Service (RLaaS)**. Other services (clients) call Aries over **gRPC** to ask: *"Is this user/client allowed to make a request right now?"*

Aries responds with a simple boolean — `allowed: true` or `allowed: false` — in microseconds.

### The Problem It Solves

Without rate limiting, any API is vulnerable to:

| Threat | Description |
|---|---|
| **DDoS Attacks** | Malicious actors flood the server with requests, crashing it |
| **Abuse / Scraping** | Bad actors extract all your data cheaply |
| **Thundering Herd** | A sudden burst of legitimate traffic after an outage brings the system down again |
| **Cost Overruns** | Unbounded API calls to expensive downstream services (AI, payment, SMS) |
| **Fairness Violations** | Power users hog shared resources, degrading experience for others |

Rate limiting is a **circuit breaker at the API gateway layer**.

---

## 2. Why "as a Service"?

Instead of each microservice implementing its own rate limiter (which would be duplicated logic and stateless per-instance), Aries is a **centralized, stateful rate limiter** that all services in the ecosystem consult. This is the **RLaaS pattern** used by companies like:

- **Stripe** — 100 req/s per API key
- **GitHub** — 5000 req/hour for authenticated users
- **Cloudflare** — Zone-level global rate limiting
- **AWS API Gateway** — Throttling limits per stage
- **Google Cloud Endpoints** — Quota management

### In-Process vs. RLaaS Pattern

```
❌ In-Process (Anti-Pattern for distributed systems):
   Service A → its own counter → allowed?  // Fails with 3 instances!
   Service B → its own counter → allowed?  // Each instance has its own state

✅ RLaaS (Aries Pattern):
   Service A --> gRPC call --> Aries --> Redis (shared state) --> allowed?
   Service B --> gRPC call --> Aries --> Redis (shared state) --> allowed?
   // All instances see the same shared token count!
```

---

## 3. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     CLIENT MICROSERVICES                      │
│  (e-commerce, auth-service, payment-service, user-service)   │
└──────────────┬───────────────────────────────────────────────┘
               │  gRPC (HTTP/2, Protobuf) port 9090
               ▼
┌──────────────────────────────────────────────────────────────┐
│                    ARIES RATE LIMITER SERVICE                 │
│                                                              │
│  ┌─────────────────┐    ┌──────────────────────────────────┐ │
│  │  GrpcRateLimiter │    │      RateLimiterService          │ │
│  │  Service         │───▶│  (Business Logic + Micrometer)   │ │
│  │  @GrpcService    │    └──────────────┬───────────────────┘ │
│  └─────────────────┘                   │                     │
│                                        │ execute Lua Script  │
│  ┌──────────────────────────────────────▼─────────────────┐  │
│  │               RedisConfig (tokenBucketScript Bean)      │  │
│  │               tokens_bucket.lua                         │  │
│  └──────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌──────────────┐   HTTP/REST port 8000                     │
│  │ AriesTest     │   /ratecheck  /increment                  │
│  │ Controller    │   (for manual testing & debugging)       │
│  └──────────────┘                                           │
│                                                              │
│  ┌──────────────┐                                           │
│  │ Actuator     │──▶ /actuator/prometheus (metrics scrape)  │
│  └──────────────┘                                           │
└──────────────────────────────────────────────────────────────┘
               │  Redis commands (HMGET, HMSET, EXPIRE)
               ▼
┌──────────────────────────────────────────────────────────────┐
│                         REDIS                                 │
│  key: "rate_limit:user1"                                     │
│  hash fields: { tokens: 4, last_refill: 1714000000000 }     │
└──────────────────────────────────────────────────────────────┘
               
               │  Prometheus scrapes /actuator/prometheus every 5s
               ▼
┌──────────────────────────────────────────────────────────────┐
│                       PROMETHEUS                              │
│   aries_rate_limiter_allowed_total                           │
│   aries_rate_limiter_blocked_total                           │
└──────────────────────────────────────────────────────────────┘
               │  (can be extended with Grafana dashboard)
               ▼
         [GRAFANA DASHBOARD]
```

---

## 4. What Aries Is NOT

| What you might think | What Aries actually is |
|---|---|
| An API gateway | No — it's a specialized microservice gateway CALLS |
| An application with a UI | No — it's a pure backend service |
| Stateless | No — it maintains per-user token counts in Redis |
| Language-specific | No — gRPC + Protobuf means any language can call it |
| A proxy/sidecar | No — clients call it explicitly (can be made sidecar) |

---

## 5. Key Technical Decisions & Why

### Decision 1: gRPC over REST for the Rate-Limiting API

| Criterion | REST/HTTP | gRPC/HTTP2 |
|---|---|---|
| Serialization | JSON (text, verbose) | Protobuf (binary, compact) |
| Latency | Higher | Lower (binary + multiplexing) |
| Type Safety | None (schema optional) | Enforced by `.proto` contract |
| Code Generation | Manual/Swagger | Automatic from `.proto` |
| Streaming | Not native | Native bi-directional |

Rate limiting is on the **critical path** of every request. Every microsecond matters. gRPC with Protobuf binary encoding is **~3-10x faster** than JSON REST for this use case.

### Decision 2: Redis Lua Script over Java-level Redis calls

```
❌ Java-level (RACE CONDITION):
   1. GET tokens         → 5
   2. GET last_refill    → ...
   [Another thread does the SAME here — both see 5 tokens]
   3. SET tokens, 4      → Both set to 4 (only consumed 1 total!)

✅ Lua Script (ATOMIC):
   EVALSHA script [key] [capacity] [rate] [interval] [now]
   → All 6 Redis operations execute atomically as ONE command
   → Redis is single-threaded — no race condition possible
```

### Decision 3: Spring Virtual Threads (Java 21)

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Java 21 Virtual Threads (Project Loom) mean:
- gRPC handler threads are **virtual** (lightweight, ~1KB vs 1MB platform threads)
- Under 10,000 concurrent gRPC connections, no thread pool exhaustion
- No need for reactive programming complexity

### Decision 4: Micrometer for Metrics Abstraction

Micrometer is the **SLF4J for metrics**. We write `meterRegistry.counter(...)` and it works with Prometheus, Datadog, CloudWatch, Graphite — without changing business code.

---

## 6. Project Identity Card

| Property | Value |
|---|---|
| **Project Name** | Aries |
| **Type** | Distributed Rate Limiter as a Service (RLaaS) |
| **Language** | Java 21 |
| **Build Tool** | Maven 3.x |
| **Framework** | Spring Boot 3.5.13 |
| **API Protocol** | gRPC (primary) + REST HTTP (secondary/debug) |
| **Storage** | Redis (shared distributed state) |
| **Scripting** | Lua (atomic Redis operations) |
| **Monitoring** | Micrometer + Prometheus |
| **Algorithm** | Token Bucket |
| **gRPC Port** | 9090 |
| **HTTP Port** | 8000 |
| **Thread Model** | Java 21 Virtual Threads |
