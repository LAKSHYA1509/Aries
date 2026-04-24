# 🔢 Rate Limiting Algorithms — Deep Dive

> This is the **core intellectual content** of Aries. Master this chapter completely — interviewers LOVE asking about the theory and tradeoffs of rate limiting algorithms.

---

## 1. What Is Rate Limiting?

Rate limiting is a technique to **control the rate** at which a system processes requests. It enforces a **maximum number of requests** a client can make within a **defined time window**.

### Rate Limiting vs Throttling vs Circuit Breaking

| Concept | Definition | Scope |
|---|---|---|
| **Rate Limiting** | Cap requests per time unit | Client-level |
| **Throttling** | Shape traffic flow (slow down, don't reject) | System-level |
| **Circuit Breaking** | Stop calls when downstream fails | Service-level |
| **Bulkhead** | Isolate failures to a partition | Resource-level |

---

## 2. The Four Classic Rate Limiting Algorithms

### Algorithm 1: Fixed Window Counter

```
Time: |-- Window 1 ---|-- Window 2 ---|-- Window 3 ---|
       0s            10s            20s             30s
       
Window 1: req1, req2, req3, req4, req5 → ALLOWED (5/5)
Window 1: req6 → BLOCKED (5/5 consumed)
Window 2: req7 → ALLOWED (1/5 in new window)
```

**Implementation**:
```
key = "user:userId:window:" + (now / windowSize)
count = INCR key
if count == 1: EXPIRE key windowSize
if count > limit: REJECT
```

**Problem — Burst at window boundary**:
```
Window 1: [9s] 5 requests ALLOWED
Window 2: [10s] 5 requests ALLOWED
→ 10 requests in 2 seconds! (2x the limit)
```

**Not used in Aries**, but understand it for comparison questions.

---

### Algorithm 2: Sliding Window Log

```
Keep a sorted set of timestamps of all recent requests.
On each request:
1. Remove all timestamps older than now - windowSize
2. Count remaining entries
3. If count < limit: add current timestamp, allow
4. Else: reject
```

**Redis Implementation**:
```bash
ZREMRANGEBYSCORE key 0 (now - windowSize)
count = ZCARD key
if count < limit:
    ZADD key now uuid
    ALLOW
else:
    REJECT
```

**Advantage**: Perfectly smooth, no boundary bursts
**Disadvantage**: Memory usage = O(requests per window) — can be large

---

### Algorithm 3: Sliding Window Counter

A hybrid approach. Uses two fixed windows and interpolates:
```
allowed = count_current_window + count_prev_window * (windowSize - timeInCurrentWindow) / windowSize
```

**Space efficient** but **approximate** (not exact).

---

### Algorithm 4: Token Bucket ← **Aries Uses This**

This is the algorithm Aries implements. Understand it deeply.

---

## 3. Token Bucket Algorithm — Complete Analysis

### The Mental Model

Think of a **leaky bucket** (physical metaphor):

```
┌─────────────────────────────────┐
│         Token Bucket            │
│                                 │
│  [●][●][●][●][●]  ← Capacity: 5 │
│                                 │
│  Refill rate: 1 token / second  │
└─────────────────────────────────┘

Every second: +1 token (up to capacity)
Every request: -1 token
No tokens? → Request REJECTED (429)
```

### Formal Definition

```
State:
  tokens(t)      = current token count at time t
  last_refill(t) = timestamp of last refill

On each request at time t_now:
  1. time_elapsed = t_now - last_refill
  2. tokens_to_add = floor(time_elapsed / refill_interval) * refill_rate
  3. tokens = min(capacity, tokens + tokens_to_add)
  4. if tokens_to_add > 0: last_refill += tokens_to_add * refill_interval
  5. if tokens >= 1: tokens--; return ALLOW
     else: return REJECT
```

### Why Token Bucket Is Ideal for Rate Limiting

| Property | Token Bucket | Fixed Window | Sliding Log |
|---|---|---|---|
| Allows bursts up to capacity | ✅ | ✅ | ❌ |
| Smooth average rate over time | ✅ | ❌ (boundary spike) | ✅ |
| Memory per user | O(1) — just 2 values | O(1) | O(requests) |
| Computation | O(1) | O(1) | O(log n) |
| Accurate to last ms | ✅ | ❌ | ✅ |

---

## 4. Aries Implementation 1: Java-Level Token Bucket (`TokenBucket.java`)

This is the **educational/baseline** implementation. It demonstrates the algorithm without an atomic Lua script.

```java
// algorithms/TokenBucket.java
@Component
public class TokenBucket {
    private final StringRedisTemplate redisTemplate;

    public boolean isAllowed(String userId) {
        long now = System.currentTimeMillis() / 1000; // seconds
        int maxICanGive = 5;                           // capacity
        int refillRate = 1;                            // 1 token/second

        String tokenKey = "token:" + userId;
        String timeKey  = "timestamp:" + userId;

        // Step 1: Read current state from Redis
        String tokenStr = redisTemplate.opsForValue().get(tokenKey);
        String timeStr  = redisTemplate.opsForValue().get(timeKey);

        // Step 2: Parse or initialize
        Integer tokens         = (tokenStr != null)  ? Integer.parseInt(tokenStr)  : maxICanGive;
        Long    lastRefillTime = (timeStr  != null)  ? Long.parseLong(timeStr)     : now;

        // Step 3: Calculate refill
        long timePassed  = now - lastRefillTime;
        int  tokensToAdd = (int) (timePassed * refillRate);

        if (tokensToAdd > 0) {
            tokens         = Math.min(maxICanGive, tokens + tokensToAdd);
            lastRefillTime = now;
        }

        // Step 4: Consume token or reject
        if (tokens > 0) {
            tokens--;
            redisTemplate.opsForValue().set(tokenKey, tokens.toString());
            redisTemplate.opsForValue().set(timeKey, lastRefillTime.toString());
            return true;
        }
        return false;
    }
}
```

### Critical Problem: Race Condition

```
Time:  T1                           T2
Thread A:  GET token:user1 → "5"   
Thread B:                           GET token:user1 → "5"  (same!)
Thread A:  SET token:user1 → "4"   
Thread B:                           SET token:user1 → "4"  (both think they got a token!)
Result: 2 requests allowed, but only 1 token consumed.
```

**This violates the "at most N requests" guarantee.** This is why the production path uses Lua.

### When Is This Class Used?
Per the code comments: *"This is class level implementations for Token Bucket Algorithm that isn't made from Lua Script to learn actual things."*

It's a **learning implementation**, not used by `RateLimiterService`. The production service uses the Lua script.

---

## 5. Aries Implementation 2: Lua Script (`tokens_bucket.lua`) — PRODUCTION

```lua
-- src/main/resources/tokens_bucket.lua
local key             = KEYS[1]                -- "rate_limit:user1"
local capacity        = tonumber(ARGV[1])      -- 5     (max tokens)
local refill_rate     = tonumber(ARGV[2])      -- 1     (tokens per interval)
local refill_interval = tonumber(ARGV[3])      -- 1000  (ms per refill cycle)
local now             = tonumber(ARGV[4])      -- current time in ms

-- Step 1: Read current state atomically (HMGET = Hash Multi-GET)
local bucket      = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens      = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

-- Step 2: Initialize if first request
if tokens == nil then
    tokens      = capacity   -- Start with full bucket
    last_refill = now
end

-- Step 3: Calculate how many tokens to refill
local time_passed = now - last_refill
local refills     = math.floor(time_passed / refill_interval)  -- integer refill cycles

if refills > 0 then
    tokens      = math.min(capacity, tokens + (refills * refill_rate))
    last_refill = last_refill + (refills * refill_interval)  -- advance last_refill precisely
end

-- Step 4: Try to consume a token
local allowed = 0
if tokens >= 1 then
    tokens  = tokens - 1
    allowed = 1
end

-- Step 5: Persist state and set TTL
redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)
redis.call('EXPIRE', key, 60)  -- Auto-delete key after 60s of inactivity

-- Step 6: Return result
return {allowed}  -- Returns table; Spring maps table[1] to Long
```

### Java Invocation

```java
// RateLimiterService.java
public boolean isAllowed(String userId) {
    String key = "rate_limit:" + userId;

    Long result = redisTemplate.execute(
        tokenBucketScript,          // the loaded Lua script Bean
        List.of(key),               // KEYS array
        "5",                        // ARGV[1] = capacity
        "1",                        // ARGV[2] = refill_rate
        "1000",                     // ARGV[3] = refill_interval (1 second = 1000ms)
        String.valueOf(System.currentTimeMillis())  // ARGV[4] = current timestamp in ms
    );

    boolean allowed = result != null && result == 1;
    // ... metrics
    return allowed;
}
```

### Lua vs Java: Key Differences

| Aspect | Java TokenBucket | Lua Script |
|---|---|---|
| Atomicity | ❌ Race condition possible | ✅ Atomic — Redis single-threaded |
| Redis operations | 4 (2 GET + 2 SET) | 2 (1 HMGET + 1 HMSET) |
| Hash vs String | String keys (2 keys per user) | Hash (1 key per user) |
| Time precision | Seconds | Milliseconds |
| Refill calculation | Floor via integer cast | `math.floor()` |
| TTL management | Not handled | `EXPIRE 60` auto-cleanup |
| Return type | `boolean` | `Long` (1 or 0) |

### Why `last_refill = last_refill + (refills * refill_interval)` Not `= now`?

This is a subtlety interviewers might probe:

```
Scenario: Refill interval = 1000ms, last_refill = 0ms, now = 2500ms

Option A: last_refill = now (2500ms)
  refills = floor(2500/1000) = 2 tokens added ✅
  But next call at 3000ms: time_passed = 500ms, refills = 0
  Problem: Lost 500ms of partial token credit!

Option B: last_refill = 0 + 2 * 1000 = 2000ms  ← Aries does this
  Next call at 3000ms: time_passed = 1000ms, refills = 1
  Token correctly added after 1 full second.
```

Option B **preserves the sub-interval remainder** — tokens are never "lost" due to floating point or alignment.

---

## 6. Rate Limiting Strategies by Use Case

| Use Case | Algorithm | Reason |
|---|---|---|
| API Gateway per-user | Token Bucket | Allows bursts, smooth average |
| Login attempts | Fixed Window | Simple, burst prevention |
| Email sending | Leaky Bucket (variant) | Smooth flow, no bursts |
| DDoS protection | Sliding Window Counter | Approximate + efficient |
| Credit card transactions | Sliding Window Log | Exact accuracy required |

### Aries Configuration in Code

```java
// Current hardcoded values in RateLimiterService:
"5",   // capacity: 5 tokens max
"1",   // refill_rate: 1 token per interval
"1000" // refill_interval: 1000ms = 1 second

// This means: 5 req burst, then 1 req/second sustained rate
```

**Interview Question**: "How would you make this configurable?"
> "I would externalize these as `@ConfigurationProperties`, loaded from `application.yml`, allowing per-client or per-tier rate limits without code changes."

---

## 7. Rate Limiting Parameters — Configuration Space

```
capacity = 5       → 5-token burst allowance
rate     = 1 token / 1 second = 1 req/s sustained rate

Client behavior scenarios:
1. 5 requests instantly → ALL allowed (exhausts burst budget)
6th request immediately → BLOCKED (429)
After 1 second         → 1 token refilled, 1 more allowed

2. 1 request every 2 seconds → ALWAYS allowed (refill faster than consume)

3. 30 requests in 1 second → First 5 allowed, next 25 blocked
```

---

## 8. HTTP Response for Rate-Limited Requests

```java
// AriesTestController.java
@GetMapping("/ratecheck")
public ResponseEntity<String> rateCheck() {
    boolean check = rateLimiterService.isAllowed("user1");
    if (check) {
        return ResponseEntity.ok("Here is your Data");  // 200 OK
    } else {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)  // 429
                             .body("Too many requests");
    }
}
```

**HTTP 429 Too Many Requests** is the correct status code per RFC 6585.

**Production Enhancement**: Add `Retry-After` header:
```java
.header("Retry-After", "1")  // Client should retry after 1 second
```
