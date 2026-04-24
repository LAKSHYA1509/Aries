# 🧪 Testing Strategy — Unit, Integration & Stress Testing

> Testing separates professional engineers from beginners. Know exactly what each test does and why.

---

## 1. Testing Overview

Aries has three levels of testing:

| Level | File | Type | Purpose |
|---|---|---|---|
| **Smoke** | `AriesApplicationTests.java` | Integration | Verify Spring context loads |
| **Stress** | `GrpcStressTest.java` | Performance/Manual | Verify concurrency and correct rate-limiting |
| **Unit** | (Not yet written) | Unit | Isolate business logic |

---

## 2. `AriesApplicationTests.java` — Context Load Test

```java
package com.aries;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AriesApplicationTests {

    @Test
    void contextLoads() {
        // Empty body — the test passes if the application context loads without exception
    }
}
```

### What `@SpringBootTest` Does

The `@SpringBootTest` annotation:
1. Starts the **full Spring ApplicationContext** (all beans, all auto-configuration)
2. Starts an **embedded web server** (Tomcat on random/configured port)
3. Starts a **Netty gRPC server**
4. Requires a **real Redis connection** (unless mocked)

This is an **integration test**, not a unit test. It validates:
- All beans can be instantiated
- Dependency injection wiring is correct
- Configuration is valid
- Redis connection is available
- gRPC server can start on its port

### When `contextLoads()` Would Fail

```
1. Redis is not running → RedisConnectionFailureException → FAIL
2. Port 9090 already in use → gRPC server can't start → FAIL
3. Missing bean dependency → NoSuchBeanDefinitionException → FAIL
4. Invalid configuration → IllegalStateException → FAIL
5. Lua file not found → ClassPathResource missing → FAIL
```

### Making It More Robust

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// uses random port to avoid conflicts with running applications

// OR mock Redis to avoid needing a real Redis:
@SpringBootTest
@TestPropertySource(properties = {
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379"
})
class AriesApplicationTests {
    @Test
    void contextLoads() {}
}
```

---

## 3. `GrpcStressTest.java` — Concurrency Correctness Test

```java
package com.aries.test;

import com.aries.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.*;

public class GrpcStressTest {

    public static void main(String[] args) throws Exception {

        // Create gRPC channel to running Aries instance
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 9090)
                .usePlaintext()
                .build();

        // Blocking stub (synchronous calls)
        RateLimiterServiceGrpc.RateLimiterServiceBlockingStub stub =
                RateLimiterServiceGrpc.newBlockingStub(channel);

        // Thread pool: 50 concurrent "users"
        ExecutorService executor = Executors.newFixedThreadPool(50);

        // Submit 100 tasks
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                RateLimitRequest request = RateLimitRequest.newBuilder()
                        .setClientId("user1")
                        .build();

                RateLimitResponse response = stub.checkLimit(request);

                System.out.println(response.getIsAllowed());
            });
        }

        executor.shutdown();
    }
}
```

### What This Test Validates

**Test scenario**:
- All 100 requests are for `"user1"` (same user)
- 50 threads run concurrently
- Capacity = 5 tokens, refill = 1/second

**Expected output**:
```
true
true
true
true
true
false
false
false
... (95x false)
```

**Critical validation**:
- Exactly **5** `true` responses (not 6, not 7 — which would happen if the race condition existed)
- Exactly **95** `false` responses
- This proves the **Lua script atomicity works correctly** under concurrent load

### Why 50 Threads + 100 Requests?

- 50 threads = enough concurrency to trigger race conditions if they existed
- 100 requests >> 5 tokens = guaranteed to exhaust the bucket
- All same userId = maximum contention on one Redis key

### Improvements to Make This a Proper Test

```java
// Better version with assertions:
AtomicInteger allowedCount = new AtomicInteger(0);
AtomicInteger blockedCount = new AtomicInteger(0);

CountDownLatch latch = new CountDownLatch(100);

for (int i = 0; i < 100; i++) {
    executor.submit(() -> {
        try {
            RateLimitResponse response = stub.checkLimit(request);
            if (response.getIsAllowed()) {
                allowedCount.incrementAndGet();
            } else {
                blockedCount.incrementAndGet();
            }
        } finally {
            latch.countDown();
        }
    });
}

latch.await();  // Wait for all 100 to complete

// Assert exactly 5 were allowed
assert allowedCount.get() == 5 : "Expected 5 allowed, got " + allowedCount.get();
assert blockedCount.get() == 95 : "Expected 95 blocked, got " + blockedCount.get();
```

### Thread Safety of gRPC Blocking Stub

The `RateLimiterServiceBlockingStub` is **thread-safe** and can be shared across threads. A single stub can be used by all 50 threads simultaneously because:
- gRPC creates separate HTTP/2 streams per call
- The blocking stub's blocking is per-call, not per-stub

---

## 4. What Tests Should Be Written (Interview Discussion)

### Unit Test: `RateLimiterService`

```java
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    
    @Mock
    private RedisScript<Long> tokenBucketScript;
    
    @Mock
    private MeterRegistry meterRegistry;
    
    @Mock
    private Counter counter;  // Mock for both allowedCounter and blockedCounter

    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString())).thenReturn(counter);
        rateLimiterService = new RateLimiterService(
            redisTemplate, tokenBucketScript, meterRegistry
        );
    }

    @Test
    void whenLuaReturns1_thenAllowed() {
        // Arrange
        when(redisTemplate.execute(
            eq(tokenBucketScript), 
            anyList(), 
            any()
        )).thenReturn(1L);

        // Act
        boolean result = rateLimiterService.isAllowed("user1");

        // Assert
        assertTrue(result);
        verify(counter, times(1)).increment();  // allowedCounter incremented
    }

    @Test
    void whenLuaReturns0_thenBlocked() {
        when(redisTemplate.execute(any(), any(), any())).thenReturn(0L);
        
        boolean result = rateLimiterService.isAllowed("user1");
        
        assertFalse(result);
    }

    @Test
    void whenLuaReturnsNull_thenBlocked() {
        when(redisTemplate.execute(any(), any(), any())).thenReturn(null);
        
        boolean result = rateLimiterService.isAllowed("user1");
        
        assertFalse(result);
    }
}
```

### Unit Test: `GrpcRateLimiterService`

```java
@ExtendWith(MockitoExtension.class)
class GrpcRateLimiterServiceTest {

    @Mock
    private RateLimiterService rateLimiterService;
    
    @Mock
    private StreamObserver<RateLimitResponse> responseObserver;

    private GrpcRateLimiterService grpcService;

    @BeforeEach
    void setUp() {
        grpcService = new GrpcRateLimiterService(rateLimiterService);
    }

    @Test
    void whenAllowed_thenResponseIsAllowed() {
        // Arrange
        when(rateLimiterService.isAllowed("user1")).thenReturn(true);
        RateLimitRequest request = RateLimitRequest.newBuilder()
            .setClientId("user1").build();

        // Act
        grpcService.checkLimit(request, responseObserver);

        // Assert
        ArgumentCaptor<RateLimitResponse> captor = 
            ArgumentCaptor.forClass(RateLimitResponse.class);
        verify(responseObserver).onNext(captor.capture());
        assertTrue(captor.getValue().getIsAllowed());
        verify(responseObserver).onCompleted();
    }

    @Test
    void whenBlocked_thenResponseIsNotAllowed() {
        when(rateLimiterService.isAllowed("user1")).thenReturn(false);
        RateLimitRequest request = RateLimitRequest.newBuilder()
            .setClientId("user1").build();

        grpcService.checkLimit(request, responseObserver);

        ArgumentCaptor<RateLimitResponse> captor = 
            ArgumentCaptor.forClass(RateLimitResponse.class);
        verify(responseObserver).onNext(captor.capture());
        assertFalse(captor.getValue().getIsAllowed());
        verify(responseObserver).onCompleted();
    }
}
```

### Integration Test with Embedded Redis

```java
@SpringBootTest
@TestPropertySource(properties = {
    "spring.data.redis.port=6370"  // Non-standard port for test Redis
})
class RateLimiterIntegrationTest {

    @Autowired
    private RateLimiterService rateLimiterService;
    
    // Use Testcontainers for a real Redis:
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
        .withExposedPorts(6379);

    @Test
    void firstFiveRequestsAllowed() {
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiterService.isAllowed("test-user"));
        }
    }

    @Test
    void sixthRequestBlocked() {
        for (int i = 0; i < 5; i++) {
            rateLimiterService.isAllowed("another-user");
        }
        assertFalse(rateLimiterService.isAllowed("another-user"));
    }
}
```

---

## 5. Testing Tools Used

| Tool | Purpose | In Aries |
|---|---|---|
| JUnit 5 | Test framework | ✅ Via `spring-boot-starter-test` |
| Mockito | Mocking framework | ✅ Via `spring-boot-starter-test` |
| Spring Boot Test | Context loading, MockMvc | ✅ `@SpringBootTest` |
| AssertJ | Fluent assertions | ✅ Via `spring-boot-starter-test` |
| Testcontainers | Real services in Docker | ⭕ Not added (enhancement) |

`spring-boot-starter-test` includes:
- JUnit 5
- Mockito
- AssertJ
- Spring Test
- Hamcrest
- JsonPath

---

## 6. Test Execution

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=AriesApplicationTests

# Run with verbose output
mvn test -Dsurefire.useFile=false

# Skip tests (e.g., during development)
mvn install -DskipTests
```

### Test Phase in Maven Lifecycle

```
validate → compile → test-compile → test → package → install → deploy
                                    ↑
                          Tests run here
```
