# 🏛️ System Design — Scaling Aries to Production

> This chapter is for when interviewers ask "How would you design this at scale?" or "Walk me through how you'd make this production-ready."

---

## 1. Current Architecture Limitations

```
CURRENT STATE (Single Instance):

Client → gRPC → Aries (1 instance) → Redis (1 instance)
                        ↑
              Single Point of Failure
              Limited throughput (~10K RPS)
```

| Limitation | Impact |
|---|---|
| Single Aries instance | SPOF, max ~10K RPS |
| Single Redis instance | SPOF, data loss on failure |
| Hardcoded rate limits | No per-user/per-tier config |
| No TLS | Vulnerable in transit |
| No auth on gRPC | Any service can call Aries |
| No retry-after | Clients can't know when to retry |

---

## 2. Phase 1: High Availability

```
                    ┌─────────────────────────────────────────────┐
                    │            Load Balancer                    │
                    │    (Kubernetes Service / AWS ALB)           │
                    └──────┬─────────────┬───────────────────────┘
                           │             │
                           ▼             ▼
                    ┌──────────┐  ┌──────────┐
                    │ Aries #1 │  │ Aries #2 │  ← Horizontal scaling
                    └──────────┘  └──────────┘
                         │              │
                         └──────┬───────┘
                                ▼
                    ┌─────────────────────────┐
                    │    Redis Sentinel        │
                    │  ┌───────┐ ┌─────────┐  │
                    │  │Master │ │Replica 1│  │  ← Automatic failover
                    │  └───────┘ └─────────┘  │
                    │            ┌─────────┐  │
                    │            │Replica 2│  │
                    │            └─────────┘  │
                    └─────────────────────────┘
```

### Changes Needed

```yaml
# application.yml — Redis Sentinel config
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - sentinel1:26379
          - sentinel2:26379
          - sentinel3:26379
      password: ${REDIS_PASSWORD}
```

### gRPC mTLS (Mutual TLS)

```yaml
# application.yml — Enable TLS on gRPC server
grpc:
  server:
    port: 9090
    security:
      enabled: true
      certificateChainPath: /certs/server.crt
      privateKeyPath: /certs/server.key
```

Client-side:
```java
// Remove usePlaintext(), add TLS
ManagedChannelBuilder
    .forAddress("aries-service", 9090)
    .useTransportSecurity()
    .build();
```

---

## 3. Phase 2: Dynamic Configuration

### Problem with Current Design

Currently rate limit parameters are hardcoded:
```java
"5", "1", "1000"  // capacity, rate, interval — same for ALL users
```

### Solution: Config Service + Per-User Limits

```java
// New class: RateLimitConfig
@Value
public class RateLimitConfig {
    String userId;
    int capacity;
    int refillRate;
    int refillIntervalMs;
    String tier; // FREE, PRO, ENTERPRISE
}

// Config stored in Redis or PostgreSQL
// Example: {userId: "user1", capacity: 10, tier: "PRO"}
//          {tier: "FREE", capacity: 5, refillRate: 1, interval: 1000}
//          {tier: "ENTERPRISE", capacity: 1000, refillRate: 100, interval: 1000}

// In RateLimiterService:
public boolean isAllowed(String userId) {
    RateLimitConfig config = configService.getConfig(userId);  // cached lookup
    
    Long result = redisTemplate.execute(
        tokenBucketScript,
        List.of("rate_limit:" + userId),
        String.valueOf(config.getCapacity()),
        String.valueOf(config.getRefillRate()),
        String.valueOf(config.getRefillIntervalMs()),
        String.valueOf(System.currentTimeMillis())
    );
    // ...
}
```

---

## 4. Phase 3: Redis Cluster for Horizontal Scaling

```
Redis Cluster (6 nodes, 3 shards):

Shard 1 (slots 0-5461):       rate_limit:user1 → node A
Shard 2 (slots 5462-10922):   rate_limit:user5000 → node B  
Shard 3 (slots 10923-16383):  rate_limit:user9999 → node C

Each Shard has 1 master + 1 replica
```

### Key Design for Cluster Compatibility

In Redis Cluster, `EVALSHA` scripts can only touch keys in the **same hash slot**. The Lua script uses one key (`rate_limit:{userId}`), so it's automatically cluster-compatible.

If you needed multiple keys (e.g., `rate_limit:user1` and `config:user1`), you'd use **hash tags**:
```
{user1}:rate_limit  →  hash slotted by "user1"
{user1}:config      →  same hash slot as above!
```

---

## 5. Phase 4: Async Metrics + Event Streaming

### Current Metrics (Synchronous, Per-Decision)

```java
allowedCounter.increment();  // Runs inline with every request
```

### Enhanced: Async Event Streaming

For high-volume scenarios, publish rate limit events to Kafka:

```java
// Publish asynchronously (non-blocking)
@Async
public void publishEvent(String userId, boolean allowed, long timestamp) {
    RateLimitEvent event = new RateLimitEvent(userId, allowed, timestamp);
    kafkaTemplate.send("rate-limit-events", userId, event);
}

// Downstream consumers can:
// - Build per-user usage reports
// - Detect anomalies (sudden burst from a user)
// - Feed into abuse detection ML models
// - Generate invoices (usage-based billing)
```

---

## 6. Phase 5: gRPC Service Mesh Integration

With a service mesh (Istio, Linkerd):

```
        Service Mesh Sidecar (Envoy)
Client ─────────────────────────────▶ Aries
       ─┤mTLS termination
         │gRPC load balancing
         │circuit breaking
         │retry policy
         │distributed tracing (auto-injected headers)
```

The mesh handles cross-cutting concerns; Aries focuses purely on business logic.

---

## 7. Complete Production Architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│                     PRODUCTION ARIES ARCHITECTURE                     │
│                                                                       │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐                          │
│  │ Service A│   │ Service B│   │ Service C│  ← Your microservices    │
│  └─────┬────┘   └─────┬────┘   └─────┬────┘                          │
│        │         gRPC + mTLS          │                               │
│        └──────────────┼──────────────┘                               │
│                       ▼                                               │
│         ┌─────────────────────────┐                                   │
│         │  gRPC Load Balancer     │  (Kubernetes ClusterIP Service) │ │
│         └─────────┬───────────────┘                                   │
│               ┌───┴───┐                                               │
│               ▼       ▼                                               │
│         ┌──────────┐ ┌──────────┐                                    │
│         │ Aries #1 │ │ Aries #2 │  ← Auto-scaled pods               │
│         └──────────┘ └──────────┘                                    │
│               │           │                                           │
│               └─────┬─────┘                                          │
│                     ▼                                                 │
│         ┌────────────────────────┐                                    │
│         │    Redis Cluster        │  (6 nodes, 3 shards)             │
│         └────────────────────────┘                                    │
│                     │                                                 │
│               ┌─────┴──────┐                                         │
│               ▼            ▼                                          │
│         ┌──────────┐  ┌──────────┐                                   │
│         │Prometheus│  │ Grafana  │  ← Monitoring + Dashboards        │
│         └──────────┘  └──────────┘                                   │
│                     │                                                 │
│               ┌─────┴──────┐                                         │
│               ▼            ▼                                          │
│         ┌──────────┐  ┌──────────┐                                   │
│         │PagerDuty │  │  Slack   │  ← Alerting                      │
│         └──────────┘  └──────────┘                                   │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 8. Capacity Planning

### Single Aries Instance Capacity

```
Assumptions:
- Redis EVALSHA latency: ~0.5ms (local network)
- gRPC Protocol overhead: ~0.1ms
- Virtual thread schedule: ~0.01ms
- Total per-request: ~0.6ms

Concurrency:
- 10,000 virtual threads handling 0.6ms tasks
- Throughput = 10,000 / 0.0006 = ~16,667 RPS per instance

Redis capacity:
- Redis handles ~100,000 simple ops/second
- Each Aries request = 1 EVALSHA (1 HMGET + 1 HMSET internally = 2 ops)
- Redis throughput bottleneck: 100,000 / 2 = 50,000 RPS

Practical single-instance capacity: ~15,000 RPS (before Redis saturates)
```

### Scaling Math

```
Target: 1,000,000 RPS

Aries instances needed: 1,000,000 / 15,000 ≈ 67 instances
Redis instances needed: 1,000,000 * 2 ops / 100,000 ops/node = 20 nodes
(organized as 10 shards × 1 master + 1 replica)
```

---

## 9. SLA / Reliability Targets

| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.99% (52 min/year downtime) | `/actuator/health` UP |
| P99 latency | < 2ms | Prometheus histogram |
| P50 latency | < 0.5ms | Prometheus histogram |
| False positives (incorrectly blocked) | < 0.001% | rate_limiter_allowed / total |
| False negatives (incorrectly allowed) | 0% (Lua atomicity) | Stress test verification |
| Redis MTTR (failure to recovery) | < 30s | Sentinel failover time |

---

## 10. Interview: System Design Whiteboard Template

When asked "Design a rate limiter for a system handling 1M RPS":

```
Step 1: Clarify requirements (2-3 minutes)
  - Per-user or per-IP?
  - Fixed limit or tiered?
  - Hard limit or soft limit?
  - Read-heavy or write-heavy?
  - Acceptable error rate on limit enforcement?

Step 2: Estimate scale (1-2 minutes)
  - 1M RPS → X instances of service
  - Data model: how many unique users? (state estimation)

Step 3: Algorithm choice (2-3 minutes)
  - Token Bucket for bursts + smooth long-term rate ✅ (Aries)
  - Explain tradeoffs vs alternatives

Step 4: Storage choice (2-3 minutes)
  - Redis for sub-ms, atomic Lua, TTL ✅
  - Cluster for horizontal scaling

Step 5: API design (2-3 minutes)
  - gRPC unary for low-latency, type-safe calls
  - Proto contract: RateLimitRequest, RateLimitResponse

Step 6: Failure scenarios (3-5 minutes)
  - Redis fails → fail-open strategy
  - Aries instance fails → LB removes, clients retry
  - Network partition → explain consistency model

Step 7: Observability (1-2 minutes)
  - Metrics: allowed/blocked counters, latency histogram
  - Alerts: block rate > 90%, Redis latency > 5ms
```
