# 📊 Observability — Micrometer, Prometheus & Metrics

> Observability is what separates a hobby project from a production system. Aries has built-in metrics instrumentation using Micrometer + Prometheus — know this deeply.

---

## 1. The Observability Pillars

Modern production systems have three pillars of observability:

| Pillar | Tool/Approach | Aries |
|---|---|---|
| **Metrics** | Prometheus + Micrometer | ✅ Fully implemented |
| **Logging** | SLF4J + Logback (default) | ✅ DEBUG level for com.aries |
| **Tracing** | Zipkin, Jaeger (not added) | ❌ Not implemented (could add) |

---

## 2. Micrometer — The Metrics Facade

### What Is Micrometer?

Micrometer is to metrics what **SLF4J is to logging** — a vendor-neutral facade.

```
Your Code  →  Micrometer API  →  [Prometheus]
                              →  [Datadog]
                              →  [CloudWatch]
                              →  [InfluxDB]
                              →  [Graphite]
                              →  [Any backend]
```

Without Micrometer, you'd write Prometheus-specific code. If you switch to Datadog, you'd rewrite all metric code.  
With Micrometer, you write `meterRegistry.counter(...)` once — changing the backend just changes a dependency.

### Dependencies in Aries

```xml
<!-- Spring Boot Actuator — includes Micrometer core -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Micrometer Prometheus Registry — the Prometheus backend -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

When `micrometer-registry-prometheus` is on the classpath, Spring Boot's auto-configuration:
1. Creates a `PrometheusMeterRegistry` bean
2. Exposes `/actuator/prometheus` endpoint via Actuator

---

## 3. Custom Metrics in `RateLimiterService`

### Metric Registration at Construction Time

```java
public RateLimiterService(
        RedisTemplate<String, String> redisTemplate,
        RedisScript<Long> tokenBucketScript,
        MeterRegistry meterRegistry) {
    
    this.redisTemplate     = redisTemplate;
    this.tokenBucketScript = tokenBucketScript;
    
    // Register counters at bean construction — they're available immediately
    this.allowedCounter = meterRegistry.counter("rate_limiter.allowed");
    this.blockedCounter = meterRegistry.counter("rate_limiter.blocked");
}
```

**Why register in constructor** (not in a method)?
- Counters are registered when the bean is created — they appear in `/actuator/prometheus` from application startup
- Avoids null pointer issues if called before initialization
- Consistent with Spring bean lifecycle

### Metric Increment in Business Logic

```java
public boolean isAllowed(String userId) {
    // ... Redis Lua execution ...

    boolean allowed = result != null && result == 1;

    if (allowed) {
        allowedCounter.increment();   // +1 to rate_limiter_allowed_total
    } else {
        blockedCounter.increment();   // +1 to rate_limiter_blocked_total
    }
    return allowed;
}
```

**Every single rate-limit decision** is recorded. Over time, this builds a picture of system usage.

---

## 4. Prometheus Data Model

### Metric Types in Prometheus

| Type | Description | Example |
|---|---|---|
| **Counter** | Monotonically increasing | Total requests, errors |
| **Gauge** | Can go up and down | Queue size, heap size |
| **Histogram** | Samples + configurable buckets | Request latency |
| **Summary** | Like histogram + quantiles | 99th percentile latency |

Aries's `rate_limiter.allowed` and `rate_limiter.blocked` are **Counters**.

### Prometheus Naming Conventions

Micrometer translates dot-notation to underscore-notation with `_total` suffix for counters:

```
Java:       meterRegistry.counter("rate_limiter.allowed")
Prometheus: rate_limiter_allowed_total
```

Full Prometheus metric line format:
```
# HELP rate_limiter_allowed_total  
# TYPE rate_limiter_allowed_total counter
rate_limiter_allowed_total 142.0
rate_limiter_blocked_total 857.0
```

---

## 5. Spring Boot Actuator

### What Actuator Provides

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "*"   # Expose all endpoints
```

Key endpoints exposed:

| Endpoint | URL | Purpose |
|---|---|---|
| Health | `/actuator/health` | Application health status (UP/DOWN) |
| Prometheus | `/actuator/prometheus` | Prometheus-format metrics |
| Metrics | `/actuator/metrics` | All metrics as JSON |
| Info | `/actuator/info` | Application metadata |
| Env | `/actuator/env` | Environment variables |
| Beans | `/actuator/beans` | All Spring beans |
| Mappings | `/actuator/mappings` | All HTTP/gRPC endpoints |

### `/actuator/health` Response

```json
{
  "status": "UP",
  "components": {
    "redis": { "status": "UP" },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

Redis health is auto-detected by Spring Boot Redis Actuator health contributor.

### `/actuator/prometheus` Sample Output

```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 4.1943040E7
jvm_memory_used_bytes{area="nonheap",id="Metaspace",} 6.7108864E7

# HELP process_cpu_usage The recent cpu usage for the Java Virtual Machine process
# TYPE process_cpu_usage gauge
process_cpu_usage 0.00423

# HELP rate_limiter_allowed_total
# TYPE rate_limiter_allowed_total counter
rate_limiter_allowed_total 142.0

# HELP rate_limiter_blocked_total
# TYPE rate_limiter_blocked_total counter
rate_limiter_blocked_total 857.0

# HELP http_server_requests_seconds
# TYPE http_server_requests_seconds summary
http_server_requests_seconds_count{method="GET",status="200",uri="/ratecheck",} 142.0
http_server_requests_seconds_sum{method="GET",status="200",uri="/ratecheck",} 0.412
http_server_requests_seconds_count{method="GET",status="429",uri="/ratecheck",} 857.0
```

---

## 6. Prometheus Configuration

```yaml
# prometheus.yml
global:
  scrape_interval: 5s          # Pull metrics every 5 seconds

scrape_configs:
  - job_name: 'aries'          # Label added to all scraped metrics
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8000']
```

### Pull Model Deep Dive

```
Prometheus (Docker container) 
    │ Every 5 seconds: GET http://host.docker.internal:8000/actuator/prometheus
    ▼
Aries (Host machine, port 8000)
    │ Returns Prometheus exposition format text
    ▼
Prometheus stores in time-series database (TSDB)
    │ Queryable via PromQL
    ▼
Dashboard (Grafana — optional integration)
```

### `host.docker.internal`

A DNS name that Docker provides inside containers. It resolves to the **host machine's IP address**, allowing Docker containers to access services running on the host.

Without this, Prometheus inside Docker couldn't reach Aries running on `localhost` of the host.

### Running Prometheus with Docker

```bash
# The command that was debugged in a previous conversation:
docker run -d \
  --name prometheus \
  -p 9090:9090 \
  -v "C:\Projects\Aries\Aries\prometheus.yml:/etc/prometheus/prometheus.yml" \
  prom/prometheus
```

**The bug that was fixed**: `promethus.yml` (typo) vs `prometheus.yml` (correct filename). Docker volume mount failed because the file on the host was named with a typo.

---

## 7. PromQL — Prometheus Query Language

### Useful Queries for Aries

```promql
# Current rate of allowed requests (per second, 1-minute window)
rate(rate_limiter_allowed_total[1m])

# Current rate of blocked requests (per second, 1-minute window)
rate(rate_limiter_blocked_total[1m])

# Ratio of blocked to total requests (block rate)
rate(rate_limiter_blocked_total[5m]) 
    / 
(rate(rate_limiter_allowed_total[5m]) + rate(rate_limiter_blocked_total[5m]))

# HTTP 429 response rate
rate(http_server_requests_seconds_count{status="429"}[5m])

# Average JVM heap usage
avg(jvm_memory_used_bytes{area="heap"})

# gRPC server call rate (if grpc metrics are enabled)
rate(grpc_server_calls_total[5m])
```

### `rate()` vs `increase()`

| Function | Description |
|---|---|
| `rate(counter[5m])` | Per-second rate averaged over 5 minutes |
| `increase(counter[5m])` | Total increase over 5 minutes |
| `irate(counter[5m])` | Instant rate (last 2 data points) |

For dashboards: use `rate()` for smooth graphs, `irate()` for spike detection.

---

## 8. Logging Configuration

```yaml
logging:
  level:
    com.aries: DEBUG   # Debug logs for all Aries classes
```

This means:
- All classes in `com.aries` package log at DEBUG level and above (DEBUG, INFO, WARN, ERROR)
- Library classes (Spring, Redis, gRPC) log at default INFO level

### Log Levels (from most to least verbose)

```
TRACE → DEBUG → INFO → WARN → ERROR → OFF

com.aries: DEBUG  →  DEBUG, INFO, WARN, ERROR all visible
Default:   INFO   →  INFO, WARN, ERROR visible (DEBUG suppressed)
```

### Production Logging Best Practice

```yaml
# Production: Only INFO+ for everything
logging:
  level:
    root: INFO
    com.aries: INFO  # Or WARN in very high traffic scenarios
```

---

## 9. Metrics Enhancement Ideas (Interview Discussion)

### Add Tags/Labels to Counters

```java
// Current (no tags):
meterRegistry.counter("rate_limiter.allowed")

// Enhanced (with tags — enables filtering in Prometheus/Grafana):
meterRegistry.counter("rate_limiter.allowed", 
    "user_tier", "premium",        // Premium vs free user
    "endpoint", "/api/search",     // Which endpoint was rate-limited
    "region", "us-east-1"          // Which region
)
```

Tags enable queries like:
```promql
# Only blocked premium users
rate(rate_limiter_blocked_total{user_tier="premium"}[5m])
```

### Add Timer for Redis Latency

```java
Timer redisTimer = Timer.builder("redis.lua.execution")
    .description("Time to execute the token bucket Lua script")
    .register(meterRegistry);

// Wrap the Redis call:
Long result = redisTimer.recordCallable(() -> 
    redisTemplate.execute(tokenBucketScript, List.of(key), ...)
);
```

### Add Remaining Tokens as a Gauge (Advanced)

```java
// Return remaining tokens from Lua script
// Update redis to return {allowed, remaining_tokens}
// Then expose as gauge for monitoring bucket health
Gauge.builder("rate_limiter.tokens.remaining", 
    tokenCountRef, AtomicInteger::get)
    .register(meterRegistry);
```
