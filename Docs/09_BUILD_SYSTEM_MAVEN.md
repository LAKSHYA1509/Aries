# ⚙️ Build System — Maven, Protobuf Compilation & Project Structure

> Maven build configuration is often overlooked but frequently asked about. Every line in `pom.xml` is intentional.

---

## 1. Maven Build Lifecycle

Maven defines a standard build lifecycle:

```
validate → initialize → generate-sources → process-sources →
generate-resources → process-resources → compile → process-classes →
generate-test-sources → process-test-sources → test-compile →
process-test-classes → test → prepare-package → package →
verify → install → deploy
```

### What Aries Uses

```bash
mvn clean install
# clean:   Delete target/ directory
# install: Run full lifecycle up to 'install' (puts JAR in local .m2 cache)
```

### Phases Relevant to Aries

| Phase | What Happens |
|---|---|
| `generate-sources` | `protobuf-maven-plugin` runs `protoc`, generates Java from `.proto` |
| `compile` | `maven-compiler-plugin` compiles all .java files (including generated) |
| `test-compile` | Compiles test classes |
| `test` | Runs JUnit tests |
| `package` | Creates executable JAR with `spring-boot-maven-plugin` |
| `install` | Copies JAR to local Maven repository (`~/.m2/repository`) |

---

## 2. `pom.xml` — Every Element Explained

### Project Coordinates

```xml
<groupId>com.aries</groupId>
<artifactId>Aries</artifactId>
<version>0.0.1-SNAPSHOT</version>
```

- `groupId`: Organization identifier (reverse domain — `com.aries`)
- `artifactId`: Project identifier (`Aries`)
- `version`: `SNAPSHOT` means in-development (vs `RELEASE` for production)

The resulting JAR: `Aries-0.0.1-SNAPSHOT.jar`

### Parent POM (BOM)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.13</version>
    <relativePath/>
</parent>
```

`spring-boot-starter-parent` is a **Bill of Materials (BOM)** — it defines compatible versions of:
- Spring Framework 6.x
- Micrometer
- Lettuce
- Jackson
- JUnit 5
- ...and 200+ other libraries

This means you DON'T need to specify versions for most Spring dependencies (they're managed).

### Java Version Property

```xml
<properties>
    <java.version>21</java.version>
</properties>
```

This property is read by `spring-boot-starter-parent` to configure:
- `maven-compiler-plugin` `source` and `target` to 21
- Enables Java 21 language features (records, sealed classes, pattern matching, virtual threads)

**Bug fixed in conversation**: Initially the compiler defaulted to Java 17. Explicitly setting `<java.version>21</java.version>` or configuring `maven-compiler-plugin` explicitly fixed the issue.

---

## 3. Dependencies Deep Dive

### Dependency 1: `spring-boot-starter-web`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

Transitively includes:
- `spring-boot-starter-tomcat` (embedded Tomcat server)
- `spring-webmvc` (Spring MVC framework)
- `jackson-databind` (JSON serialization)
- `spring-boot-starter` (core Spring)

This powers the REST endpoints (`/ratecheck`, `/increment`).

### Dependency 2: `spring-boot-devtools`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>     <!-- Only in runtime classpath, not compile -->
    <optional>true</optional>  <!-- Not transitive to dependent projects -->
</dependency>
```

Development-only features:
- **Auto-restart**: Detects class changes and restarts the application
- **LiveReload**: Browser refresh on resource changes
- **Property defaults**: Disables caching (Thymeleaf, etc.) in development

**NOT included in production JAR** because `optional:true` and `spring-boot-maven-plugin` excludes it.

### Dependency 3: `spring-boot-starter-actuator`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Enables:
- `/actuator/health` — health checks
- `/actuator/prometheus` — metrics scrape (when Prometheus registry is present)
- `/actuator/metrics` — metrics browser
- Micrometer `MeterRegistry` bean (auto-configured)

### Dependency 4: `micrometer-registry-prometheus`

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

- Adds Prometheus as a Micrometer backend
- Auto-configures `PrometheusMeterRegistry`
- Exposes Prometheus exposition format at `/actuator/prometheus`

### Dependency 5: `grpc-spring-boot-starter`

```xml
<dependency>
    <groupId>net.devh</groupId>
    <artifactId>grpc-spring-boot-starter</artifactId>
    <version>3.1.0.RELEASE</version>
    <scope>compile</scope>
</dependency>
```

**Note**: This has an explicit version because it's NOT managed by Spring Boot's parent BOM. The `scope:compile` is default but explicit.

Transitively includes:
- `io.grpc:grpc-netty-shaded` (Netty gRPC transport)
- `io.grpc:grpc-stub` (stub classes)
- `io.grpc:grpc-protobuf` (Protobuf integration)
- `com.google.protobuf:protobuf-java`

### Dependency 6: `javax.annotation-api`

```xml
<dependency>
    <groupId>javax.annotation</groupId>
    <artifactId>javax.annotation-api</artifactId>
    <version>1.3.2</version>
</dependency>
```

**Why needed?** The gRPC-generated code uses `@javax.annotation.Generated` annotation. Java 11+ removed `javax.annotation` from the JDK (it was in `java.xml.ws.annotation` module). Without this dependency, compilation fails with `cannot find symbol: @Generated`.

This is a **boilerplate fix** required for gRPC with Java 11+.

### Dependency 7: `spring-boot-starter-data-redis`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

Transitively includes:
- `spring-data-redis` (RedisTemplate, repositories)
- `lettuce-core` (Redis client)
- `commons-pool2` (connection pool support)

Auto-configures:
- `LettuceConnectionFactory` (connection factory)
- `RedisTemplate<Object, Object>` (generic)
- `StringRedisTemplate` (String-specialized)

### Dependency 8: `lombok`

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>  <!-- Compile-time only, not in final JAR -->
</dependency>
```

Annotation processor that generates boilerplate code:
- `@Getter`, `@Setter` → generates getters/setters
- `@Builder` → Builder pattern
- `@Data` → `@Getter + @Setter + @EqualsAndHashCode + @ToString`
- `@Slf4j` → injects `private static final Logger log`

### Dependency 9: `spring-boot-starter-test`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>  <!-- Only in test classpath -->
</dependency>
```

Test scope means: **not included in production JAR**. Includes JUnit 5, Mockito, AssertJ, Spring Test.

---

## 4. Build Plugins Deep Dive

### Plugin 1: `os-maven-plugin` (Extension)

```xml
<extensions>
    <extension>
        <groupId>kr.motd.maven</groupId>
        <artifactId>os-maven-plugin</artifactId>
        <version>1.7.0</version>
    </extension>
</extensions>
```

**How it works**: During Maven initialization, this plugin detects:
- OS family: `windows`, `linux`, `osx`
- Architecture: `x86_64`, `aarch64`
- Sets Maven property: `${os.detected.classifier}` = `windows-x86_64`

**Why needed**: The Protobuf compiler (`protoc`) and gRPC Java plugin are **native binaries**, not JARs. The right binary must be downloaded for the current OS.

### Plugin 2: `protobuf-maven-plugin`

```xml
<plugin>
    <groupId>org.xolstice.maven.plugins</groupId>
    <artifactId>protobuf-maven-plugin</artifactId>
    <version>0.6.1</version>
    <configuration>
        <clearOutputDirectory>false</clearOutputDirectory>
        <protocArtifact>
            com.google.protobuf:protoc:3.25.3:exe:${os.detected.classifier}
        </protocArtifact>
        <pluginId>grpc-java</pluginId>
        <pluginArtifact>
            io.grpc:protoc-gen-grpc-java:1.62.2:exe:${os.detected.classifier}
        </pluginArtifact>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>compile</goal>         <!-- Generates message classes from .proto -->
                <goal>compile-custom</goal>  <!-- Generates gRPC stub classes -->
            </goals>
        </execution>
    </executions>
</plugin>
```

**`protocArtifact`**: Downloads `protoc` compiler binary from Maven Central as a classifier artifact:
```
com.google.protobuf:protoc:3.25.3:exe:windows-x86_64
= GroupId:ArtifactId:Version:Type:Classifier
```

**`pluginArtifact`**: Downloads the gRPC Java code generator plugin for `protoc`.

**`clearOutputDirectory: false`**: Don't delete previously generated files on each build (avoids issues with partial compilation).

**`compile` goal**: Processes `src/main/proto/*.proto` → `target/generated-sources/protobuf/java/`
**`compile-custom` goal**: Generates gRPC stubs → `target/generated-sources/protobuf/grpc-java/`

Both output directories are added to the Java compilation source path automatically.

### Plugin 3: `spring-boot-maven-plugin`

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <excludes>
            <exclude>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
            </exclude>
        </excludes>
    </configuration>
</plugin>
```

Creates the **Fat JAR** (uber JAR, executable JAR):
- Packages all dependencies inside the JAR
- Makes it runnable with `java -jar Aries-0.0.1-SNAPSHOT.jar`
- Excludes Lombok (compile-time only tool, not needed at runtime)

**Regular JAR vs Fat JAR**:

```
Regular JAR:
  Aries-0.0.1-SNAPSHOT.jar
  └── com/aries/*.class  (only our classes)
  Requires all dependencies on classpath separately

Fat JAR (Spring Boot):
  Aries-0.0.1-SNAPSHOT.jar
  └── BOOT-INF/
      ├── classes/com/aries/*.class  (our classes)
      └── lib/
          ├── spring-web-6.x.jar
          ├── redis-client.jar
          ├── grpc-netty.jar
          └── ... (ALL dependencies)
  Runs standalone: java -jar Aries-0.0.1-SNAPSHOT.jar
```

### Plugin 4: `maven-compiler-plugin`

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <executions>
        <execution>
            <id>default-compile</id>
            <phase>compile</phase>
            <goals><goal>compile</goal></goals>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </execution>
        <!-- Same for default-testCompile -->
    </executions>
</plugin>
```

The `annotationProcessorPaths` configuration ensures Lombok's annotation processor runs during compilation. Without this, `@Getter`, `@Builder`, etc., would silently fail in some configurations.

**Bug fixed**: Initially, the Maven compiler was defaulting to Java 17 even though `JAVA_HOME` pointed to Java 21. Adding `<source>21</source><target>21</target>` (or relying on the parent BOM's `<java.version>21</java.version>`) fixed this.

---

## 5. Build Directory Structure

```
target/
├── generated-sources/
│   └── protobuf/
│       ├── java/com/aries/proto/
│       │   ├── RateLimitRequest.java        ← Generated from .proto
│       │   ├── RateLimitResponse.java       ← Generated from .proto
│       │   └── RateLimiterServiceOuterClass.java
│       └── grpc-java/com/aries/proto/
│           └── RateLimiterServiceGrpc.java  ← Generated gRPC stubs
├── classes/
│   ├── com/aries/
│   │   ├── AriesApplication.class
│   │   ├── algorithms/TokenBucket.class
│   │   ├── config/RedisConfig.class
│   │   ├── controller/AriesTestController.class
│   │   └── service/
│   │       ├── GrpcRateLimiterService.class
│   │       └── RateLimiterService.class
│   ├── application.yml
│   └── tokens_bucket.lua
├── test-classes/
│   └── com/aries/
│       ├── AriesApplicationTests.class
│       └── test/GrpcStressTest.class
└── Aries-0.0.1-SNAPSHOT.jar   ← Final executable Fat JAR
```

---

## 6. Running the Application

```bash
# Development — with Maven
mvn spring-boot:run

# Development — run the JAR directly
java -jar target/Aries-0.0.1-SNAPSHOT.jar

# With environment variables
REDIS_HOST=my-redis-server HTTP_PORT=8080 java -jar target/Aries-0.0.1-SNAPSHOT.jar

# With JVM arguments for virtual thread debugging (Java 21)
java -Djdk.virtualThreadScheduler.parallelism=4 -jar target/Aries-0.0.1-SNAPSHOT.jar
```
