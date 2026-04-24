# 🗄️ Redis Deep Dive — Data Structures, Atomicity & Lua Scripting

> Redis is the backbone of Aries's distributed state. This chapter covers everything from data structures to Lua atomicity to TTL management.

---

## 1. Redis Architecture

### Why Redis Is Not a Cache Here

Common misconception: "Redis is just a cache."

In Aries, Redis is the **source of truth** — the authoritative store for token counts. This is different from using Redis as a cache:

| Role | Cache Pattern | Aries Pattern |
|---|---|---|
| Data origin | Source of truth in DB | **Redis IS the source of truth** |
| On cache miss | Fetch from DB | Initialize new bucket (defaults) |
| Data loss | Temporary slowdown | Token counts reset (acceptable) |
| Consistency | Eventual | **Strong (per-user key)** |
| Expiry | On eviction | Explicit TTL (60s inactivity) |

### Redis Data Model

Redis is a **key-value store** where values can be complex data structures:

| Data Type | Redis Command Family | Aries Usage |
|---|---|---|
| String | GET, SET, INCR | `TokenBucket.java` (tokens, timestamp), `/increment` endpoint |
| Hash | HMGET, HMSET | Lua script (tokens + last_refill per user) |
| List | LPUSH, RPOP | Not used |
| Set | SADD, SMEMBERS | Not used |
| Sorted Set | ZADD, ZRANGEBYSCORE | Not used (would be used for Sliding Window Log) |
| Stream | XADD, XREAD | Not used |

---

## 2. Hash vs String: Why the Lua Script Uses Hash

### Java `TokenBucket.java` — String Approach (2 keys per user)

```
Redis keys:
  "token:user1"     → "4"              (String: current tokens)
  "timestamp:user1" → "1714000000"     (String: last refill time)

Redis commands per request:
  GET token:user1         (1 network round trip)
  GET timestamp:user1     (2 network round trips!)
  SET token:user1 "3"     (3 network round trips)
  SET timestamp:user1 "..." (4 network round trips!)
```

### Lua Script — Hash Approach (1 key per user)

```
Redis key:
  "rate_limit:user1" → { tokens: "4", last_refill: "1714000000000" }

Redis commands per request:
  HMGET rate_limit:user1 tokens last_refill  (1 network round trip — fetches both!)
  HMSET rate_limit:user1 tokens 3 last_refill ... (2 network round trips — sets both!)
```

**Hash approach is better because**:
1. **50% fewer Redis round trips** (2 vs 4)
2. **Logical grouping** of related data under one key
3. **Single TTL** manages both fields via one `EXPIRE`
4. **Atomic multi-field operations** with HMGET/HMSET

---

## 3. The Lua Script — Line-by-Line Analysis

```lua
local key             = KEYS[1]               -- "rate_limit:user1"
local capacity        = tonumber(ARGV[1])      -- 5 (max tokens in bucket)
local refill_rate     = tonumber(ARGV[2])      -- 1 (tokens added per interval)
local refill_interval = tonumber(ARGV[3])      -- 1000 (milliseconds per refill cycle)
local now             = tonumber(ARGV[4])      -- e.g., 1714000005000 (current time in ms)
```

**`KEYS` vs `ARGV`**:
- `KEYS`: Keys that the script will access — important for Redis Cluster (keys must be on same slot)
- `ARGV`: Additional arguments (not keys) — configuration parameters

```lua
local bucket      = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens      = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])
```

**`redis.call` vs `redis.pcall`**:
- `redis.call`: Raises error if command fails (bubbles up)
- `redis.pcall`: Returns error object (can be caught with pcall/error handling)

Aries uses `redis.call` — if Redis fails, it should fail loud.

**`tonumber()`**: Lua converts the returned String from Redis to a number. Returns `nil` if the string is `nil` (first request case).

```lua
if tokens == nil then
    tokens      = capacity   -- full bucket on first request
    last_refill = now
end
```

**First-request initialization**: A brand-new user gets a full bucket. This is intentional — new users shouldn't be rate-limited from the start.

```lua
local time_passed = now - last_refill
local refills     = math.floor(time_passed / refill_interval)

if refills > 0 then
    tokens      = math.min(capacity, tokens + (refills * refill_rate))
    last_refill = last_refill + (refills * refill_interval)
end
```

**Refill calculation**:
- `time_passed`: milliseconds since last refill
- `refills`: integer number of complete refill intervals elapsed
- `math.floor()`: Ensures only COMPLETE intervals contribute (no fractional tokens)
- `math.min(capacity, ...)`: Cap at max capacity (bucket can't overflow)
- `last_refill += refills * interval`: Advance by exact complete intervals (preserves remainder)

```lua
local allowed = 0
if tokens >= 1 then
    tokens  = tokens - 1
    allowed = 1
end
```

**Token consumption**: If at least 1 token available, consume it and mark as allowed.

```lua
redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)
redis.call('EXPIRE', key, 60)
return {allowed}
```

**State persistence**:
- `HMSET`: Atomically set both fields (single command)
- `EXPIRE 60`: Set TTL to 60 seconds — if no requests for 60s, key is auto-deleted (saves memory)
- `return {allowed}`: Returns a Lua table; Spring's `DefaultRedisScript` takes `table[1]` as the `Long` result

---

## 4. Atomicity in Redis — The Most Critical Interview Topic

### What Does Atomic Mean?

An atomic operation is **indivisible** — it runs completely or not at all, with no partial state visible to other operations.

### Redis Single-Threaded Execution Model

```
Redis Event Loop (Single Thread):
  ┌─────────────────────────────────────────────────┐
  │  Command Queue:                                 │
  │  [1] HMGET rate_limit:user1 tokens last_refill  │
  │  [2] HMGET rate_limit:user1 tokens last_refill  │  (another client)
  │  [3] HMSET rate_limit:user1 ...                 │
  │                                                 │
  │  Executed ONE AT A TIME, in order               │
  └─────────────────────────────────────────────────┘
```

Redis processes commands **sequentially** in a single event loop thread. This means:
- **No two commands can execute simultaneously**
- **But separate commands from different clients CAN interleave**!

### Why Individual Commands Aren't Enough

```
Client A: GET rate_limit:user1 → tokens=1    (Step 1)
Client B: GET rate_limit:user1 → tokens=1    (Step 2 — sees same value!)
Client A: HMSET rate_limit:user1 tokens=0   (Step 3)
Client B: HMSET rate_limit:user1 tokens=0   (Step 4 — also sets to 0, not -1!)
Result: TWO requests processed, but only ONE token consumed! 🐛
```

### Why Lua Scripts ARE Atomic

When Redis executes a Lua script:
1. It blocks ALL other commands until the script finishes
2. No other client can interleave between Lua `redis.call()` statements
3. The script is effectively a **serializable unit of work**

```
Client A: EVALSHA <script> → Lua starts...
           [no other commands execute during this]
           HMGET rate_limit:user1 → 1 token
           HMSET rate_limit:user1 → 0 tokens
           ...Lua finishes
Client B: EVALSHA <script> → Now Lua starts for B
           HMGET rate_limit:user1 → 0 tokens (sees A's write!)
           → allowed = 0, returns false
```

**Result: Exactly 1 token consumed by A, B correctly rejected.**

### Alternatives to Lua for Atomicity

| Approach | Atomic? | Notes |
|---|---|---|
| `MULTI/EXEC` (Redis Transaction) | ⚠️ Partial | No conditional logic inside MULTI/EXEC |
| Single command (`INCR`) | ✅ | Only for simple increment |
| **Lua Script** | ✅ | Full conditional logic, multiple commands |
| Redis Modules (`RedisBloom`) | ✅ | Pre-built algorithms, not available everywhere |
| Distributed Lock (SETNX) | ✅ | Extra latency, deadlock risk |

Lua scripts are the **best practice** for compound atomic operations in Redis.

---

## 5. Redis TTL Management

```lua
redis.call('EXPIRE', key, 60)
```

### Why TTL?

Without TTL, inactive users accumulate keys forever:
- 1M users × 2 fields per user = 2M Redis entries
- Memory usage grows indefinitely

With `EXPIRE 60`:
- After 60 seconds of inactivity, the key is automatically deleted
- When that user returns, they get a fresh full bucket (re-initialized)
- This is the correct behavior: long-inactive users shouldn't be penalized

### TTL vs Eviction

| | TTL (`EXPIRE`) | Eviction Policy |
|---|---|---|
| Trigger | Time-based | Memory pressure |
| Predictable | ✅ Yes | ❌ Depends on memory |
| Aries uses | ✅ TTL | Redis default policy |

### `EXPIRE` vs `EXPIREAT` vs `PEXPIRE`

```bash
EXPIRE  key 60      # 60 seconds from now
EXPIREAT key 1714000060  # Unix timestamp
PEXPIRE  key 60000  # 60,000 milliseconds = 60 seconds
PEXPIREAT key ...   # millisecond precision Unix timestamp
```

---

## 6. Redis Key Naming Strategy

### Aries Key Patterns

| Key Pattern | Data Type | Used By |
|---|---|---|
| `rate_limit:{userId}` | Hash (tokens, last_refill) | Lua Script (production) |
| `token:{userId}` | String | Java TokenBucket (educational) |
| `timestamp:{userId}` | String | Java TokenBucket (educational) |
| `test_counter` | String | AriesTestController.increment() |

### Key Naming Best Practices (shown in Aries)

1. **Namespace prefix**: `rate_limit:` groups all rate limit keys
2. **Separator**: Use `:` as the namespace separator (Redis convention)
3. **Readable identifier**: `{userId}` is human-readable in `redis-cli`
4. **Avoid special characters**: No spaces, no `@` signs

---

## 7. Redis Deployment in Aries

### Configuration

```yaml
# application.yml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

### Lettuce — The Default Redis Client

Spring Boot auto-configures Lettuce (not Jedis) for Redis in Spring Boot 3.x:

```
Lettuce Features:
- Non-blocking, reactive-capable Redis client
- Thread-safe (single connection shared across threads)
- Connection pool via Apache Commons Pool2 (optional)
- Redis Cluster and Sentinel support
- Automatic reconnection on connection loss
```

### Running Redis for Development

```bash
# Docker command to start Redis
docker run -d --name redis-aries -p 6379:6379 redis:latest

# Connect and verify
docker exec -it redis-aries redis-cli
127.0.0.1:6379> PING
PONG
127.0.0.1:6379> KEYS rate_limit:*  # View all rate limit keys
```

### Verifying Aries's Redis State

```bash
# After calling GET /ratecheck a few times:
127.0.0.1:6379> HGETALL rate_limit:user1
1) "tokens"
2) "2"
3) "last_refill"
4) "1714000005000"

127.0.0.1:6379> TTL rate_limit:user1
58  # Seconds remaining before key expires
```

---

## 8. Redis High Availability (Production Considerations)

Aries currently connects to a single Redis instance. In production:

### Redis Sentinel (High Availability)

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379, sentinel2:26379, sentinel3:26379
```

- Automatic failover: If master fails, Sentinel promotes a replica
- No client reconfiguration needed (Sentinel provides master address)

### Redis Cluster (Horizontal Scaling)

```yaml
spring:
  data:
    redis:
      cluster:
        nodes: cluster1:7001, cluster2:7002, cluster3:7003
```

- Data sharded across multiple nodes by key hash slot
- 16384 hash slots distributed across nodes
- `{userId}` in key name ensures user's data stays on same node (hash tag pattern)

### Redis vs Alternatives for Rate Limiting

| Store | Latency | Atomic? | Distributed? | In Aries |
|---|---|---|---|---|
| **Redis** | Sub-ms | ✅ Lua | ✅ | ✅ |
| Memcached | Sub-ms | ❌ | ✅ (limited) | ❌ |
| Hazelcast | ~1ms | ✅ | ✅ | ❌ |
| PostgreSQL | 1-10ms | ✅ | ✅ | ❌ |
| In-memory (HashMap) | Nanoseconds | ✅ (synchronized) | ❌ Fails with multiple instances | ❌ |
