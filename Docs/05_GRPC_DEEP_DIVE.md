# 🔌 gRPC Deep Dive — Protocol, Protobuf & Communication

> gRPC is the most technically complex and impressive part of Aries. This chapter covers everything an interviewer can ask about gRPC.

---

## 1. gRPC Fundamentals

### What is RPC?

Remote Procedure Call (RPC) is a communication pattern where code calls a function that executes on a **remote machine** but looks like a local function call.

```java
// Local call
boolean allowed = rateLimiterService.isAllowed("user1");

// gRPC Remote call (from client perspective — looks identical!)
RateLimitResponse response = stub.checkLimit(
    RateLimitRequest.newBuilder().setClientId("user1").build()
);
boolean allowed = response.getIsAllowed();
```

### gRPC Architecture

```
CLIENT                                    SERVER (Aries)
  │                                            │
  │─── RateLimitRequest (Protobuf binary) ────▶│
  │           HTTP/2 stream                    │
  │◀── RateLimitResponse (Protobuf binary) ────│
```

### gRPC vs REST vs GraphQL

| Feature | REST | GraphQL | gRPC |
|---|---|---|---|
| Protocol | HTTP/1.1 or 2 | HTTP/1.1 or 2 | HTTP/2 ONLY |
| Serialization | JSON | JSON | Protobuf (binary) |
| Schema | OpenAPI (optional) | GraphQL Schema | `.proto` (mandatory) |
| Type safety | ❌ (need OpenAPI) | ✅ | ✅ strict |
| Code generation | ❌ (manual) | Partial | ✅ fully auto |
| Streaming | ❌ (native) | ❌ | ✅ 4 types |
| Browser support | ✅ | ✅ | ❌ (needs gRPC-web) |
| Human-readable | ✅ | ✅ | ❌ (binary) |
| Performance | Medium | Medium | **Highest** |
| Best for | Public APIs | Mobile/flexible queries | **Microservices** |

---

## 2. HTTP/2 — The Transport Layer

gRPC REQUIRES HTTP/2. Understanding why:

### HTTP/1.1 Problems

```
HTTP/1.1:
→ Request 1 (HEAD OF LINE BLOCKING — must wait for response before next request)
← Response 1
→ Request 2
← Response 2

Workaround: Open multiple TCP connections (browsers open 6-8 per domain)
```

### HTTP/2 Solutions

```
HTTP/2 (Single TCP Connection, Multiple Streams):
→ Stream 1: Request A ─────────────────────────────▶
→ Stream 2: Request B ───────────────────────────────▶
← Stream 1: Response A ◀──────────────
← Stream 2: Response B ◀──────────────────────────
```

HTTP/2 features gRPC uses:
1. **Multiplexing**: Multiple concurrent requests over ONE TCP connection
2. **Header compression (HPACK)**: Repeated headers compressed
3. **Binary framing**: More efficient than text HTTP/1.1
4. **Flow control**: Prevent fast sender from overwhelming slow receiver
5. **Persistent connection**: No TCP handshake overhead per request

---

## 3. Protocol Buffers (Protobuf) — Binary Serialization

### The `.proto` File Is the Contract

```proto
// rate_limiter.proto
syntax = "proto3";           // Proto version 3 (latest)
package aries;               // Namespace (prevents naming conflicts)

option java_package = "com.aries.proto";     // Java package for generated code
option java_multiple_files = true;           // One .java file per message/service

service RateLimiterService {
  rpc CheckLimit (RateLimitRequest) returns (RateLimitResponse);
  //  ^^^^^^^^^^^  ^^^^^^^^^^^^^^^^          ^^^^^^^^^^^^^^^^
  //  RPC method   Input message             Output message
}

message RateLimitRequest {
  string client_id = 1;   // Field type | Field name | Field number (tag)
}

message RateLimitResponse {
  bool is_allowed = 1;
}
```

### Field Numbers — Critical Detail

```proto
string client_id = 1;  // "= 1" is the FIELD NUMBER, NOT the value!
```

Field numbers (tags) are used in **binary encoding** to identify fields. They are:
- NOT the field value
- Immutable once deployed (changing breaks backward compatibility)
- Used in binary format: `[tag << 3 | wire_type][value_bytes]`
- Range 1-15: encoded in 1 byte (use for frequently occurring fields)
- Range 16-2047: encoded in 2 bytes

### Wire Format Example

```
Proto message: { client_id: "user1" }

Encoding:
  Tag = (field_number=1) << 3 | (wire_type=2 for LEN) = 0x0A
  0x0A 0x05 0x75 0x73 0x65 0x72 0x31
  │    │    └─────── "user1" in UTF-8 ───┘
  │    └── length = 5 bytes
  └── tag = field 1, wire type 2 (length-delimited)
  
Total: 7 bytes

Same in JSON: {"clientId":"user1"} = 18 bytes
Protobuf is ~2.5x smaller and faster to parse
```

### Proto3 vs Proto2

| Feature | Proto2 | Proto3 |
|---|---|---|
| Default values | No auto-defaults | Zero-value defaults (0, "", false) |
| `required` fields | Exists | Removed (all optional) |
| `optional` keyword | Explicit | Implicit |
| Backward compat | Stricter | More flexible |
| Aries uses | N/A | **Proto3** |

### Generated Java Code Structure

```java
// Generated: RateLimitRequest.java
public final class RateLimitRequest extends 
    com.google.protobuf.GeneratedMessageV3 {
    
    private volatile Object clientId_ = "";  // field storage
    
    // Builder for construction
    public static Builder newBuilder() { return DEFAULT_INSTANCE.toBuilder(); }
    
    // Getter (camelCase from snake_case)
    public String getClientId() { return (String) clientId_; }
    
    // Serialization
    public void writeTo(CodedOutputStream output) { ... }
    public static RateLimitRequest parseFrom(byte[] data) { ... }
    
    public static final class Builder extends ... {
        public Builder setClientId(String value) { ... return this; }
        public RateLimitRequest build() { ... }
    }
}
```

---

## 4. The Four Types of gRPC Communication

### Type 1: Unary RPC (What Aries Uses)

```proto
rpc CheckLimit (RateLimitRequest) returns (RateLimitResponse);
//  ^^^^^^^^^^^                                               ^
// One request, one response (like a regular function call)
```

```java
// Server: responseObserver.onNext(single_response); responseObserver.onCompleted();
// Client: response = stub.checkLimit(request);       // blocks until response
```

### Type 2: Server Streaming RPC

```proto
rpc StreamMetrics (MetricsRequest) returns (stream MetricsResponse);
```

```java
// Server: calls onNext() multiple times, then onCompleted()
// Client: Iterator<MetricsResponse> responses = stub.streamMetrics(request);
```

Use case: Real-time metric streaming, live data feeds.

### Type 3: Client Streaming RPC

```proto
rpc BatchCheckLimit (stream RateLimitRequest) returns (BatchResponse);
```

```java
// Client: sends multiple requests, server returns one summary response
// Use case: Batch rate limit checks
```

### Type 4: Bidirectional Streaming RPC

```proto
rpc RealTimeLimit (stream RateLimitRequest) returns (stream RateLimitResponse);
```

```java
// Both sides stream simultaneously over same connection
// Use case: Chat applications, real-time collaborative tools
```

**Aries uses Unary** — most appropriate for rate checking (single check → single answer).

---

## 5. `net.devh.boot.grpc-spring-boot-starter` Internals

### What This Library Does

```xml
<dependency>
    <groupId>net.devh</groupId>
    <artifactId>grpc-spring-boot-starter</artifactId>
    <version>3.1.0.RELEASE</version>
</dependency>
```

This starter:
1. **Auto-starts a Netty gRPC server** on port 9090 (configurable via `grpc.server.port`)
2. **Discovers `@GrpcService` beans** and registers them with the Netty server
3. **Provides `@GrpcClient` annotation** for injecting gRPC client stubs (if needed)
4. **Integrates with Spring lifecycle** — server starts when ApplicationContext is ready, shuts down gracefully

### Netty — The Underlying Network Framework

gRPC Java uses Netty as its transport layer. Netty is an **asynchronous, event-driven network library** based on the Reactor pattern. It handles:
- TCP connection management
- HTTP/2 frame parsing
- Thread management (Netty event loop threads)
- TLS/SSL (if configured)

For Aries, Netty handles the low-level networking while the `net.devh` starter bridges it to Spring.

### Server Port Configuration

```yaml
# application.yml (not explicitly set, default is 9090)
grpc:
  server:
    port: 9090  # default
```

The HTTP port (8000) and gRPC port (9090) are **separate ports** on the same server.

---

## 6. The `GrpcStressTest.java` — Client-Side gRPC

```java
package com.aries.test;

import com.aries.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.*;

public class GrpcStressTest {

    public static void main(String[] args) throws Exception {

        // Step 1: Create a managed channel (connection pool to gRPC server)
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 9090)
                .usePlaintext()     // No TLS (development mode)
                .build();

        // Step 2: Create blocking stub
        RateLimiterServiceGrpc.RateLimiterServiceBlockingStub stub =
                RateLimiterServiceGrpc.newBlockingStub(channel);

        // Step 3: Create thread pool simulating concurrent clients
        ExecutorService executor = Executors.newFixedThreadPool(50);

        // Step 4: Submit 100 concurrent requests
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

### `ManagedChannel` — gRPC Connection Management

```java
ManagedChannel channel = ManagedChannelBuilder
    .forAddress("localhost", 9090)
    .usePlaintext()   // Skip TLS (dev only — NEVER in production)
    .build();
```

`ManagedChannel` manages:
- Connection pool to the server
- Connection health (reconnection on failure)
- Load balancing across multiple server instances
- Graceful shutdown

**Production setup would include**:
```java
ManagedChannelBuilder
    .forAddress("aries-service", 9090)
    .useTransportSecurity()   // TLS
    .keepAliveTime(30, TimeUnit.SECONDS)
    .build();
```

### Blocking vs Async Stubs

```java
// Blocking stub — thread blocks until response
RateLimiterServiceGrpc.RateLimiterServiceBlockingStub blockingStub = 
    RateLimiterServiceGrpc.newBlockingStub(channel);
RateLimitResponse response = blockingStub.checkLimit(request);  // Blocks!

// Async stub — callback-based
RateLimiterServiceGrpc.RateLimiterServiceStub asyncStub = 
    RateLimiterServiceGrpc.newStub(channel);
asyncStub.checkLimit(request, new StreamObserver<RateLimitResponse>() {
    @Override public void onNext(RateLimitResponse value) { ... }
    @Override public void onError(Throwable t) { ... }
    @Override public void onCompleted() { ... }
});

// Future stub — returns ListenableFuture
RateLimiterServiceGrpc.RateLimiterServiceFutureStub futureStub =
    RateLimiterServiceGrpc.newFutureStub(channel);
ListenableFuture<RateLimitResponse> future = futureStub.checkLimit(request);
```

### Stress Test Analysis

The test launches **50 threads** each sending **2 requests** = **100 concurrent gRPC calls** to "user1".

Expected result (with capacity=5, refill=1/s):
- First 5 calls: `true` (allowed — consumes 5 tokens)
- Remaining 95 calls: `false` (blocked — no tokens left, no time to refill)
- After 1 second: 1 more call could succeed

This test validates:
1. gRPC server handles concurrent requests ✅
2. Redis Lua atomicity prevents token overcounting ✅  
3. 429 behavior works correctly ✅

---

## 7. gRPC Error Handling

### gRPC Status Codes (vs HTTP status codes)

| gRPC Status | HTTP Equivalent | Meaning |
|---|---|---|
| `OK` | 200 | Success |
| `INVALID_ARGUMENT` | 400 | Bad request |
| `NOT_FOUND` | 404 | Resource not found |
| `ALREADY_EXISTS` | 409 | Conflict |
| `RESOURCE_EXHAUSTED` | 429 | Rate limited! |
| `INTERNAL` | 500 | Server error |
| `UNAVAILABLE` | 503 | Server down |
| `DEADLINE_EXCEEDED` | 504 | Timeout |

**For rate limiting**, the semantically correct gRPC response is `RESOURCE_EXHAUSTED` — but Aries returns the result in the response body (`is_allowed: false`) rather than using a gRPC error status, which is a valid design choice for a dedicated RLaaS.

### How to Throw gRPC Errors (if needed)

```java
responseObserver.onError(
    Status.RESOURCE_EXHAUSTED
        .withDescription("Too many requests")
        .asRuntimeException()
);
```
