# 🎤 Interview Q&A — Master Question Bank

> This is your ultimate preparation guide. Every question here is something a senior/staff engineer could ask. Practice answering each one out loud.

---

## SECTION 1: Project Overview Questions

---

**Q1: "Tell me about the Aries project."**

> "Aries is a distributed Rate Limiter as a Service — RLaaS — that I built to solve the problem of API abuse and traffic control in a distributed environment. The core idea is: instead of every microservice implementing its own rate limiter with local state that breaks under horizontal scaling, Aries acts as a centralized, shared rate-limiting oracle. Services call Aries over gRPC and get a binary decision — allowed or not — in sub-millisecond time.
>
> Technically, I implemented the Token Bucket algorithm using a Redis Lua script for atomic, race-condition-free state management. The Lua script runs entirely within Redis's single-threaded execution model, guaranteeing that concurrent requests from multiple service instances never overconsume tokens. I also integrated Micrometer with Prometheus for real-time observability of the allowed/blocked ratios."

---

**Q2: "Why did you build a rate limiter? What problem does it solve?"**

> "Rate limiters solve several critical production problems:
> 1. **DDoS protection** — limit the blast radius of traffic floods
> 2. **API abuse** — prevent scraping or automated attacks
> 3. **Fairness** — ensure no single user can starve others of resources
> 4. **Cost control** — when calling expensive third-party APIs (AI, SMS, payments), rate limiting prevents runaway costs
> 5. **Service protection** — downstream services can only handle so much; rate limiting protects them from the thundering herd problem
>
> I chose to make it 'as a Service' because in a microservices architecture, a per-service in-memory rate limiter breaks down as soon as you scale to 2+ replicas. The shared state must live outside the services — that's Redis."

---

**Q3: "Why gRPC instead of REST for the rate-limiting API?"**

> "Rate limiting sits on the critical path of every single request in the system. Every API call must first check Aries before proceeding. So Aries's response time directly adds to the user-perceived latency of every endpoint.
>
> gRPC with Protobuf is significantly faster than REST with JSON:
> - Protobuf binary encoding is ~3-10x smaller than JSON
> - HTTP/2 multiplexing means one TCP connection handles all concurrent calls
> - No serialization/deserialization overhead of JSON parsing
> - Native code generation from `.proto` ensures type safety without OpenAPI overhead
>
> For a background service like this that never talks to browsers directly, gRPC is the clear winner. If I needed browser clients, I'd add a REST adapter or a gRPC-Web gateway in front."

---

**Q4: "How is this different from using Nginx or a CDN for rate limiting?"**

> "Nginx and CDN-level rate limiting work at the network/HTTP layer — they're excellent for protecting against raw traffic volume using IP-based rules. But they can't do application-level rate limiting:
> - They don't know which user is behind a request (no auth context)
> - They can't apply per-tier limits (free vs premium users)
> - They can't be called by internal microservices to protect their own resources
>
> Aries operates at the application layer — it knows the client ID, can apply per-user per-day limits, can be called from any service in the fleet, and can have complex rules that no network-level tool can express."

---

## SECTION 2: Algorithm Questions

---

**Q5: "Explain the Token Bucket algorithm."**

> "The Token Bucket works like a physical bucket of tokens. The bucket has a maximum capacity — say, 5 tokens. Tokens refill at a fixed rate — say, 1 token per second. Every incoming request consumes 1 token. If tokens are available, the request is allowed. If the bucket is empty, the request is rejected with a 429 Too Many Requests.
>
> The math is:
> - On each request, calculate time elapsed since last refill
> - Add `floor(elapsed / interval) * rate` tokens (capped at capacity)
> - Advance `last_refill` by the exact number of complete intervals
> - If tokens >= 1: consume one, return allowed
> - Else: return blocked
>
> The key advantage over Fixed Window is that it allows short bursts up to the capacity while still enforcing a long-term average rate. With capacity=5 and rate=1/s, a user can burst 5 requests instantly but then sustains only 1/s long-term."

---

**Q6: "What are the tradeoffs of Token Bucket vs Sliding Window Log?"**

> "Token Bucket:
> - O(1) memory per user — just 2 values (tokens, last_refill)
> - O(1) computation per request
> - Allows bursts up to capacity
> - Small approximation error due to discrete refill cycles (not continuous)
>
> Sliding Window Log:
> - O(requests per window) memory per user — stores a sorted set of timestamps
> - O(log n) computation — sorted set insertion/range query
> - Perfectly accurate — no approximation
> - No burst beyond limit possible
>
> For a general-purpose RLaaS, Token Bucket is better because: most production APIs WANT to allow reasonable bursts (users can spam-click once), the memory footprint stays flat regardless of traffic, and the accuracy difference is negligible at our scale. Sliding Window Log is better when you need EXACT fairness — like financial transaction limits."

---

**Q7: "Your Lua script has `last_refill = last_refill + (refills * refill_interval)` instead of `last_refill = now`. Why?"**

> "This is a subtle but important correctness detail about preserving sub-interval precision.
>
> If a user makes a request at T=0 (last_refill=0), and the next request comes at T=2500ms with a 1000ms interval:
>
> With `last_refill = now` (2500ms):
> - Refills = floor(2500/1000) = 2 → add 2 tokens ✅
> - Next request at T=3000ms: time_passed = 500ms, refills = 0 → token NOT added
> - The 500ms of 'credit' is lost
>
> With `last_refill += refills * interval` (= 2000ms):
> - Same 2 tokens added ✅
> - Next request at T=3000ms: time_passed = 1000ms, refills = 1 → token added ✅
> - No credit lost!
>
> The second approach correctly 'banks' the partial interval and gives it credit on the next request."

---

**Q8: "What's the race condition in your Java-level `TokenBucket.java` and how does Lua fix it?"**

> "The Java implementation does 4 separate Redis commands: GET tokens, GET timestamp, SET tokens, SET timestamp. Between any two of these, another thread or another server instance can execute its own sequence.
>
> Example: Two requests arrive simultaneously for user1 with 1 token left.
> - Thread A reads tokens=1 ✓
> - Thread B reads tokens=1 ✓ (same value, before A writes)
> - Thread A decrements → sets tokens=0
> - Thread B decrements → sets tokens=0
> - Result: both allowed, but only 1 token was available. The limit is violated!
>
> The Lua script fixes this because Redis executes the entire script atomically — it blocks all other commands while the script runs. Thread B's EVALSHA can't start until Thread A's EVALSHA completes. So Thread B will see Thread A's written value of 0 and be correctly rejected."

---

## SECTION 3: Redis Questions

---

**Q9: "Why Redis for rate limiting? Why not a database?"**

> "Four reasons:
> 1. **Speed** — Redis is in-memory, sub-millisecond latency. Rate limiting must be faster than the request it's protecting. PostgreSQL is 10-100ms per query — unacceptable overhead.
> 2. **Atomicity** — Redis supports Lua scripts that run atomically. No separate transaction management needed.
> 3. **TTL** — Redis has native key expiration. Inactive users' keys auto-delete after 60 seconds, keeping memory usage flat. With a DB, you'd need a cron job.
> 4. **Single-threaded execution** — Redis's single-threaded command processing model is the foundation of its atomicity guarantees."

---

**Q10: "What happens if Redis goes down?"**

> "Currently, Aries doesn't have explicit Redis failure handling. If Redis is unavailable:
> - `redisTemplate.execute()` throws `RedisConnectionFailureException`
> - This propagates as a 500 Internal Server Error back to the gRPC caller
>
> Production strategies for Redis failures:
> 1. **Fail open**: Catch the exception and return `allowed=true` (prefer availability over rate limiting during outages)
> 2. **Fail closed**: Let the exception propagate → callers treat it as blocked (prefer safety during outages)
> 3. **Circuit breaker with fallback** (Resilience4j): If Redis is down for 5s, switch to an in-memory rate limiter as degraded mode
> 4. **Redis Sentinel/Cluster**: Automatic failover so Redis downtime is measured in seconds, not minutes
>
> For Aries's use case (protecting APIs), fail-open during Redis outages is usually preferred — you don't want to block all traffic because your rate limiter had a blip."

---

**Q11: "Explain the EXPIRE 60 in your Lua script. What problem does it solve?"**

> "Without EXPIRE, every user who ever made a request would have a key in Redis forever. In a high-traffic system with millions of users, that's millions of keys accumulating indefinitely until Redis runs out of memory.
>
> `EXPIRE key 60` tells Redis: if no request touches this key for 60 seconds, delete it automatically.
>
> Why 60 seconds? The refill rate is 1 token/second and max capacity is 5 tokens. After 5 seconds of inactivity, the bucket would be completely refilled anyway. 60 seconds is generous — it means if a user comes back within 60 seconds, they get their correct token count. If they come back after 60 seconds, they get a fresh full bucket (which is fine — they've been gone long enough).
>
> The EXPIRE is reset on every request, so active users never lose their key."

---

**Q12: "Why use HMSET vs SET for storing the rate limiter state?"**

> "HMSET stores both `tokens` and `last_refill` as fields of a **single Hash key**. The alternative is two separate String keys.
>
> Advantages of Hash:
> 1. **50% fewer round trips** — HMGET fetches both fields in one command; HMSET updates both atomically in one command. With String keys, you need 2 GETs + 2 SETs = 4 round trips.
> 2. **Single key TTL** — one EXPIRE manages both fields. With String keys, you need EXPIRE on each key separately.
> 3. **Logical grouping** — the data belongs together; modeling it as one hash is semantically correct.
> 4. **Memory efficiency** — Redis has special memory optimizations for small hashes."

---

## SECTION 4: Spring Boot & Architecture Questions

---

**Q13: "Explain what @SpringBootApplication does."**

> "@SpringBootApplication is a compound annotation equivalent to three annotations together:
> - `@Configuration`: This class can define Spring beans with @Bean methods
> - `@EnableAutoConfiguration`: Tells Spring to scan the classpath and auto-configure matching beans — if Redis starter is present, auto-configure RedisTemplate; if gRPC starter is present, auto-start the Netty server
> - `@ComponentScan`: Scan the package of this class and all sub-packages for @Component, @Service, @Controller, @Repository annotations and register them as beans
>
> The beauty is convention over configuration — adding a dependency to pom.xml automatically enables its features, without any XML configuration."

---

**Q14: "How does Spring inject `MeterRegistry` and `RedisTemplate` into `RateLimiterService`?"**

> "This is constructor injection via Spring's IoC container. When Spring initializes `RateLimiterService`, it sees the constructor requires three parameters: `RedisTemplate<String, String>`, `RedisScript<Long>`, and `MeterRegistry`.
>
> Spring looks in its ApplicationContext:
> - `RedisTemplate<String, String>`: Auto-configured by `spring-boot-starter-data-redis` via Lettuce
> - `RedisScript<Long>`: Defined as a `@Bean` in our `RedisConfig` class
> - `MeterRegistry`: Auto-configured by `spring-boot-starter-actuator` + `micrometer-registry-prometheus` as a `PrometheusMeterRegistry`
>
> Spring resolves all three and calls the constructor. This is Dependency Inversion — `RateLimiterService` doesn't know or care if `MeterRegistry` is Prometheus vs Datadog. It just uses the interface."

---

**Q15: "What is the difference between `@Service`, `@Component`, `@Repository`, and `@Controller`?"**

> "All four are specializations of `@Component` — they all register the class as a Spring bean detected by component scanning. The difference is semantic:
> - `@Component`: Generic Spring-managed bean
> - `@Service`: Service layer — business logic
> - `@Repository`: Data access layer — also adds exception translation (converts DB exceptions to Spring's DataAccessException hierarchy)
> - `@Controller`: Presentation layer — Spring MVC controller for web requests
> - `@RestController`: `@Controller` + `@ResponseBody` — returns data directly, not view names
>
> In Aries, `RateLimiterService` uses `@Service`, `GrpcRateLimiterService` uses `@GrpcService` (Spring detection), and `AriesTestController` uses `@RestController`."

---

**Q16: "Why does Aries use constructor injection instead of @Autowired on fields?"**

> "Field injection (`@Autowired private RedisTemplate redis`) has several problems:
> 1. The field isn't `final` — it can be reassigned accidentally or by reflection
> 2. Can't test without Spring context — you can't inject mocks in unit tests
> 3. Hides dependencies — you don't see what a class needs until you look inside
> 4. Allows partial initialization — class instantiated before dependencies injected
>
> Constructor injection (Aries's approach) is the recommended best practice because:
> 1. Fields can be `final` — immutable
> 2. Dependencies are explicit — visible in constructor signature
> 3. Works with plain Java new() for unit testing
> 4. If a dependency is missing, the application fails to start (fail-fast)
>
> Spring Boot 4.x / modern Spring even removed the need for `@Autowired` on constructors — if a class has exactly one constructor, Spring automatically injects it."

---

**Q17: "What does `spring.threads.virtual.enabled: true` do?"**

> "This enables Java 21 Virtual Threads (Project Loom) for Spring Boot's request-handling threads.
>
> Without virtual threads: Every HTTP request and gRPC call gets a Platform Thread (backed by an OS thread). A typical thread pool is 200-500 threads. Beyond that, requests queue or get rejected.
>
> With virtual threads: Spring creates a Virtual Thread for each request. Virtual Threads are JVM-managed lightweight threads — ~1KB heap vs ~1MB for platform threads. You can have millions of them. When a virtual thread blocks on IO (like our Redis call), it **parks** instead of blocking the OS thread underneath.
>
> For Aries specifically: every `isAllowed()` call blocks on Redis network IO. With virtual threads, 10,000 concurrent gRPC calls don't need 10,000 OS threads — just 10,000 virtual threads that park while waiting for Redis. This dramatically improves throughput under high concurrency."

---

## SECTION 5: Scalability & Production Questions

---

**Q18: "How would you scale Aries to handle 1 million requests per second?"**

> "Current Aries is a single-service deployment. For 1M RPS:
>
> 1. **Horizontal scaling of Aries**: Run 10+ Aries instances behind a load balancer. Since state is in Redis, all instances share the same token buckets — no inconsistency.
>
> 2. **Redis scaling**:
>    - Redis Cluster with 6+ nodes (3 masters + 3 replicas)
>    - Keys are sharded by hash slot (rate_limit:user1 → slot X on node A)
>    - This gives us Redis throughput of ~100K ops/s per shard × 3 shards = 300K ops/s
>
> 3. **Lua script optimization**: Reduce Redis calls. Currently 2 calls per request (HMGET + HMSET). Could combine into pipeline or explore Redis 7.x FUNCTION commands.
>
> 4. **Local cache layer**: For very hot users (viral content), maintain a per-instance token count with periodic Redis sync. Trade slight accuracy for lower Redis load.
>
> 5. **gRPC load balancing**: Use `grpc.client.defaultLoadBalancingPolicy: round_robin` on client side, or deploy a service mesh (Envoy, Linkerd) for server-side balancing."

---

**Q19: "What would you add to make Aries production-ready?"**

> "Several things:
>
> 1. **TLS on gRPC** — Never run gRPC without TLS between services in production. Currently using `.usePlaintext()`.
>
> 2. **Per-client configuration** — Currently all users get the same limits (5 tokens, 1/s). Production needs per-API-key, per-tier, per-endpoint limits stored in a config database.
>
> 3. **Redis Sentinel / Cluster** — Single Redis is a SPOF. Sentinel for HA failover, Cluster for horizontal scaling.
>
> 4. **gRPC interceptors** — Add authentication interceptor to validate that callers are authorized to check limits.
>
> 5. **Retry-After header** — Return when the client should retry (next token available in X ms).
>
> 6. **Admin API** — Endpoint to manually reset, raise, or lower limits for a specific client in emergencies.
>
> 7. **Distributed tracing** — Add Zipkin/Jaeger integration to trace the gRPC call → Redis call chain.
>
> 8. **Alerting rules** — Prometheus alert rules: 'if block rate > 80% for 5 minutes, alert on-call.'"

---

**Q20: "Your stress test hardcodes 'user1'. What's wrong with that in production?"**

> "In production:
> 1. Different users would have different client IDs — `user1`, `user2`, `service-payments`, etc.
> 2. The key pattern `rate_limit:{userId}` creates one key per user — scalable.
> 3. A stress test should test concurrent access to the SAME key (high contention) AND to DIFFERENT keys (low contention, scaling test).
>
> The `'user1'` choice is correct for the contention test — it maximally stresses the atomic Lua logic. But a complete stress test would also:
> - Vary client IDs to test key distribution
> - Run for longer durations to observe refill behavior
> - Measure p99 latency, not just pass/fail
> - Test what happens when Redis is slow (network simulation)"

---

**Q21: "How would you implement per-endpoint or per-tier rate limits?"**

> "Two approaches:
>
> **Approach 1: Composite key**
> Change the key from `rate_limit:{userId}` to `rate_limit:{userId}:{endpoint}` or `rate_limit:{userId}:{tier}`.
> The Lua script stays the same, but is called with different limits per key.
>
> ```java
> // Per-endpoint rate limiting:
> String key = "rate_limit:" + userId + ":search";
> redisTemplate.execute(script, List.of(key), "10", "2", "1000", now);  // 10 req/s for search
>
> String key2 = "rate_limit:" + userId + ":upload"; 
> redisTemplate.execute(script, List.of(key2), "1", "1", "60000", now); // 1 per minute for upload
> ```
>
> **Approach 2: Config-driven limits**
> Store per-user/per-tier limits in a database. Fetch the config on each request (cached in memory/Redis):
> ```java
> RateLimitConfig config = configService.getConfig(userId, endpoint);
> redisTemplate.execute(script, List.of(key), 
>     String.valueOf(config.getCapacity()),
>     String.valueOf(config.getRefillRate()),
>     ...
> );
> ```"

---

**Q22: "There's a `data.json` file in your project root with `{\"client_id\": \"user1\"}`. What is that?"**

> "That's a test payload file used for manual testing of the gRPC endpoint. When using a tool like `grpcurl` or `evans` (gRPC command-line clients), you can pipe this JSON as the request body:
>
> ```bash
> grpcurl -plaintext -d @ localhost:9090 aries.RateLimiterService/CheckLimit < data.json
> ```
>
> The `-d @` flag reads from stdin, so piping `data.json` sends `{client_id: 'user1'}` as the request. It's a convenience file analogous to a Postman collection for REST APIs."

---

## SECTION 6: Bug & Debugging Questions

---

**Q23: "You mentioned fixing a ClassCastException in TokenBucket.java. Walk me through it."**

> "The original code tried to do this:
> ```java
> Integer tokens = (Integer) redisTemplate.opsForValue().get(tokenKey);
> ```
>
> `StringRedisTemplate.opsForValue().get()` always returns a `String`, never an `Integer`. You can't cast a `String` to `Integer` — that's a `ClassCastException` at runtime, not a compile error.
>
> The fix was:
> ```java
> String tokenStr = redisTemplate.opsForValue().get(tokenKey);
> Integer tokens = (tokenStr != null) ? Integer.parseInt(tokenStr) : maxICanGive;
> ```
>
> This is a common gotcha with Redis + Java. Redis stores everything as bytes/strings. Type information is not preserved. Always parse strings to the needed type. `Integer.parseInt()` and `Long.parseLong()` are the correct tools."

---

**Q24: "You had a Docker volume mount error. What was it?"**

> "The host file was named `promethus.yml` (missing an 'e' — typo: prometheus misspelled as promethus), but the Docker volume mount command specified `prometheus.yml` (correct spelling). Docker tried to mount a file that didn't exist on the host.
>
> Lesson: Docker's behavior when you specify a non-existent file in a volume mount depends on the OS and Docker version. On Linux, Docker creates an empty directory at that path instead of a file. On Windows, it often fails or creates something unexpected. The Prometheus container then couldn't find its config and failed to start.
>
> Fix: Rename the file to `prometheus.yml` to match the volume mount path."

---

**Q25: "Why did your Maven build use Java 17 when you had Java 21 installed?"**

> "Maven's compiler plugin defaults to its own configured Java version, which can lag behind what the JDK property `<java.version>21</java.version>` implies. In our case, `<java.version>21</java.version>` in `<properties>` correctly told the Spring Boot parent BOM to use Java 21, but there was a configuration mismatch in how the compiler plugin bindings were set up.
>
> More specifically, when you explicitly configure `<executions>` in `maven-compiler-plugin` without setting `<source>` and `<target>` inside those executions, Maven can revert to its default (which was previously Java 17). The fix was ensuring the compiler configuration explicitly specified Java 21 or relied correctly on the parent BOM property.
>
> Lesson: Be explicit. `<source>21</source><target>21</target>` or `--release 21` in the compiler configuration leaves no ambiguity."

---

**Q26: "What is a Protobuf compilation error and how did you fix it?"**

> "When running `mvn clean install`, the `protobuf-maven-plugin` downloads the `protoc` binary and the `protoc-gen-grpc-java` plugin for the current OS, then runs them on `rate_limiter.proto`.
>
> The error was: 'error reading RateLimiter.java — naming conflict'. The issue was a conflict between the outer class name auto-generated from the `.proto` filename and an inner message name. Protobuf, by default, creates an outer class named `RateLimiterOuterClass` (from `rate_limiter.proto`), but if you hadn't set `option java_multiple_files = true`, all messages would be inner classes and the name `RateLimiter` might conflict.
>
> The fix was adding `option java_multiple_files = true` to the `.proto` file, which generates each message as a separate `.java` file instead of inner classes, eliminating the naming conflict."

---

## SECTION 7: Scenario-Based Questions

---

**Q27: "Imagine you're asked to explain Aries to a non-technical person."**

> "Imagine a nightclub. The bouncer at the door decides who gets in. If the club is getting too crowded, the bouncer slows down entry — 'Sorry, wait 5 minutes before you can come back in.'
>
> In the tech world, Aries is that bouncer. When too many requests hit an API (the club), Aries steps in and tells some requests to wait or come back later. This protects the API from being overwhelmed, ensures everyone gets a fair share, and prevents any one user from monopolizing the system."

---

**Q28: "What would you do if the rate limiter itself becomes the bottleneck?"**

> "If Aries becomes the bottleneck:
>
> 1. **Check what's slow**: Use Prometheus to see if latency is coming from gRPC overhead, Redis Lua execution, or network hops.
>
> 2. **Scale Redis**: Is Redis CPU-bound? Add Redis Cluster sharding. Is it network-bound? Colocate Redis and Aries in the same availability zone.
>
> 3. **Batch checking**: Allow callers to check multiple client IDs in one gRPC call (batch RPC). Reduces round trips.
>
> 4. **Token reservation**: Return tokens in batches (e.g., reserve 100 tokens at once). Callers manage local countdown, only refill from Aries when exhausted. Dramatically reduces Aries call frequency.
>
> 5. **Selective limiting**: Apply rate limiting only to specific endpoints or high-risk operations. Not every API needs strict rate limiting.
>
> 6. **Shadow mode**: Allow all traffic but LOG what would have been blocked. Review the data. Rate limiters designed without data often block the wrong things."
