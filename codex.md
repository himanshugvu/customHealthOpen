# Custom Application Health Monitoring Library

Expert-level implementation plan for a Spring Boot 3.x (Java 21) custom application health monitoring library (`app-health-monitor`) and a demo parent application (`parent-app`). This spec is implementation-ready with concrete configuration keys, wiring, response schema, and build layout.

----------------------------------------------------------------------------

## Goals

- Provide pluggable, configurable health checks for Database, Kafka, and External REST endpoints.
- Expose results under `/actuator/health/custom` with per-check latency details.
- Run one-off async validation at startup and log results.
- Allow each check to be independently enabled/disabled via `app-health.yml`.
- Use only parent-provided beans (e.g., `RestClient`); the library must not create its own clients.
- Keep library version-agnostic by using starter-style dependency (parent controls versions).

## Non-Goals

- Replace Spring’s built-in `db` or `kafka` health checks; this complements them with custom logic and latency reporting.
- Provide built-in Kafka/DB servers; the demo shows configuration only.

----------------------------------------------------------------------------

## Tech Stack

- Java 21, Spring Boot 3.3.x
- Spring Actuator, Spring Configuration Processor
- HTTP client: Spring `RestClient` backed by Apache HttpClient 5 (connection pooling)
- Kafka admin: `org.apache.kafka.clients.admin.AdminClient`
- Build: Maven multi-module

----------------------------------------------------------------------------

## Module Layout

```
pom.xml (packaging: pom)
app-health-monitor/ (library)
parent-app/        (demo)
```

----------------------------------------------------------------------------

## Library: `app-health-monitor`

### Maven (`app-health-monitor/pom.xml`)

- Packaging: `jar`
- Dependencies (no versions; `provided` where relevant so parent controls versions):
  - `spring-boot-autoconfigure` (provided)
  - `spring-boot-actuator` (provided)
  - `spring-web` (provided) for `RestClient`
  - `spring-core`, `spring-context` (provided)
  - `spring-boot-configuration-processor` (optional, provided)
  - `spring-jdbc` (provided) for DB connectivity check
  - `org.apache.httpcomponents.client5:httpclient5` (provided)
  - `org.apache.kafka:kafka-clients` (provided)

Notes: The library ships compiled classes but defers dependency versions to the parent. The parent must bring Spring Boot BOM and actual versions.

### Auto-Configuration Registration

- Spring Boot 3 uses `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.example.health.AppHealthAutoConfiguration
```

### Configuration Properties

- Class: `com.example.health.config.AppHealthProperties`
- Prefix: `app.health`
- Defaults: entire feature disabled by default (`enabled=false`)

```yaml
# app-health.yml (can also be merged into application.yml)
app:
  health:
    enabled: false        # master switch
    startupLog: true      # run async checks at startup and log

    db:
      enabled: true       # only active if a DataSource is present
      validationQuery: "SELECT 1"

    kafka:
      enabled: false
      adminClientBean: myKafkaAdminClient   # bean name to use (AdminClient)

    external:
      services:
        - name: serviceA
          enabled: true                     # per-URL toggle (default true)
          restClientBean: myRestClient      # bean name (RestClient) provided by parent
          urlBean: serviceAUrl              # bean producing URI/String
        - name: serviceB
          enabled: false                    # disabled example
          restClientBean: anotherClient
          urlBean: anotherUrlBean
```

Supported URL bean types: `java.net.URI`, `java.lang.String`, or a `Supplier<URI>` bean. The library resolves by name and converts via Spring `ConversionService`.
Timeouts are not configured by the library; all timeouts/retries come from the parent-provided clients.

### Beans and Conditions

- `AppHealthAutoConfiguration`
  - Conditionally active when `app.health.enabled=true`.
  - Produces a `HealthContributor` named `custom` combining sub-indicators.
  - Produces per-check `HealthIndicator` beans conditionally:
    - `DatabaseHealthIndicator` if `DataSource` present and `app.health.db.enabled=true`.
    - `KafkaHealthIndicator` if `app.health.kafka.enabled=true` and the named `AdminClient` bean exists.
    - `ExternalServiceHealthIndicator` per configured external service whose `enabled` flag is true (default true).
  - Registers an `ApplicationRunner` that, on startup, asynchronously executes enabled checks once and logs latencies.

### Health Indicators

- Common behavior
  - Measure latency with `System.nanoTime()` (negligible overhead).
  - Do not set custom timeouts or retries; rely on parent client settings.
  - Catch and log exceptions; never propagate to break startup or the health endpoint.
  - Return details map with keys: `latencyMs`, `component`, additional context.

- `DatabaseHealthIndicator`
  - Strategy: Get connection from `DataSource`, execute configurable validation query (`SELECT 1`).
  - Status: `UP` if query succeeds within timeout, else `DOWN`.

- `KafkaHealthIndicator`
  - Expects bean name from `app.health.kafka.adminClientBean` resolving to `org.apache.kafka.clients.admin.AdminClient`.
  - Strategy: `describeCluster().nodes().get()` (timeouts governed by AdminClient configuration).
  - Status: `UP` if call succeeds; include `nodeCount` in details.

- `ExternalServiceHealthIndicator` (one per configured service)
  - Respects per-service `enabled` flag (default true). No top-level external enabled switch.
  - Resolves only parent-provided `RestClient` bean and `urlBean` (to a `URI`). The library never creates a `RestClient` of its own.
  - Performs a single HTTP `HEAD` to the resolved URI.
  - Status: `UP` for 2xx; `DOWN` otherwise. Details include `status`, `latencyMs`.
  - On any exception, log and return `DOWN` with error details; do not throw.

### Custom Health Endpoint Shape

- Location: `/actuator/health/custom` (sub-component of standard health endpoint).
- Example response:

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": { "latencyMs": 8, "component": "database" }
    },
    "kafka": {
      "status": "DOWN",
      "details": { "error": "Timeout after 2000ms", "latencyMs": 2001, "component": "kafka" }
    },
    "external": {
      "status": "UP",
      "components": {
        "serviceA": { "status": "UP", "details": { "status": 200, "latencyMs": 35, "component": "external:serviceA" } },
        "serviceB": { "status": "UP", "details": { "status": 204, "latencyMs": 22, "component": "external:serviceB" } }
      }
    }
  }
}
```

### Startup Logging

- On application start (if `app.health.startupLog=true`), run all enabled checks asynchronously and log one line per check:

```
[AppHealth] db: UP in 8ms
[AppHealth] kafka: DOWN (Timeout after 2000ms) in 2001ms
[AppHealth] external.serviceA: UP 35ms | external.serviceB: UP 22ms
```

### HTTP Client Guidance

- Parent app provides the `RestClient` bean(s). The library only resolves by bean name and never creates its own client. Example parent-side configuration using Apache HttpClient 5 with pooling:

```java
@Bean
HttpClientConnectionManager connectionManager() {
  var cm = PoolingHttpClientConnectionManagerBuilder.create()
      .setMaxConnTotal(100)
      .setMaxConnPerRoute(20)
      .build();
  return cm;
}

@Bean
CloseableHttpClient httpClient(HttpClientConnectionManager cm) {
  return HttpClients.custom().setConnectionManager(cm).evictExpiredConnections().build();
}

@Bean
RestClient myRestClient(CloseableHttpClient httpClient) {
  return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)).build();
}
```

----------------------------------------------------------------------------

## Project Layout Implemented

```
pom.xml (modules: app-health-monitor, parent-app)
app-health-monitor/  (library with auto-config + indicators)
parent-app/          (demo Spring Boot app)
docker-compose.yml   (Kafka, MongoDB, External HTTP service)
```

----------------------------------------------------------------------------

## How To Run (Local)

1) Start infra in Docker:

```
docker compose up -d
```

This brings up:
- Kafka (PLAINTEXT on localhost:9092)
- MongoDB (localhost:27017, no auth)
- External service httpbin (localhost:8090)

2) Build and run the app:

```
mvn -q -DskipTests package
mvn -q -pl parent-app spring-boot:run
```

3) Try the demo endpoints:

- Health (custom): http://localhost:8080/actuator/health/custom
- Mongo ping:       http://localhost:8080/demo/mongo/ping
- Kafka info:       http://localhost:8080/demo/kafka/info
- External ping:    http://localhost:8080/demo/external/ping

Configuration is in `parent-app/src/main/resources/application.yml`. You can override:
- `KAFKA_BOOTSTRAP` for Kafka (default `localhost:9092`)
- `MONGODB_URI` for Mongo (default `mongodb://localhost:27017/demo`)
- `EXTERNAL_URL` for external (default `http://localhost:8090/status/200`)

The library is enabled via `app.health.enabled=true`. Kafka check uses bean `kafkaAdminClient`. External uses bean `myRestClient` and URL bean `externalServiceUrl`.


The library only resolves the named `RestClient` bean; the parent app configures pooling, timeouts, and retries. The library adds no additional latency beyond a single request and timing.

----------------------------------------------------------------------------

## Demo: `parent-app`

### Maven (`parent-app/pom.xml`)

- Packaging: `jar`
- Depends on:
  - Spring Boot starter web + actuator
  - H2 database (runtime) or Postgres driver
  - Kafka clients (if demonstrating Kafka)
  - Apache HttpClient 5
  - Module dependency on `app-health-monitor`

### Configuration

`src/main/resources/application.yml`:

```yaml
server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always

spring:
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""

# Optional: import separate app-health.yml for clarity
spring.config.import: optional:classpath:app-health.yml,optional:file:./config/app-health.yml
```

`src/main/resources/app-health.yml`:

```yaml
app:
  health:
    enabled: true
    startupLog: true
    db:
      enabled: true
    kafka:
      enabled: false
      adminClientBean: myKafkaAdminClient
    external:
      services:
        - name: httpbin
          enabled: true
          restClientBean: pooledRestClient
          urlBean: httpbinUrl
```

### Demo Beans

```java
@SpringBootApplication
public class ParentAppApplication { public static void main(String[] args){ SpringApplication.run(ParentAppApplication.class,args);} }

// DataSource auto-configured by Spring based on application.yml

@Configuration
class DemoClientsConfig {
  @Bean(name = "httpbinUrl")
  URI httpbinUrl() { return URI.create("https://httpbin.org/status/204"); }

  @Bean(name = "pooledRestClient")
  RestClient restClient(CloseableHttpClient httpClient) {
    return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)).build();
  }

  @Bean
  HttpClientConnectionManager cm() { return PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(100).setMaxConnPerRoute(20).build(); }

  @Bean
  CloseableHttpClient httpClient(HttpClientConnectionManager cm) { return HttpClients.custom().setConnectionManager(cm).evictExpiredConnections().build(); }

  // Optional Kafka setup for demo (disabled by default)
  // @Bean(name = "myKafkaAdminClient")
  // AdminClient adminClient() { return AdminClient.create(Map.of("bootstrap.servers", "localhost:9092")); }
}
```

Run: `mvn -q -pl parent-app -am spring-boot:run` then GET `http://localhost:8080/actuator/health/custom`.

----------------------------------------------------------------------------

## Implementation Outline (Library)

### Files

```
app-health-monitor/
 ├─ pom.xml
 ├─ src/main/java/com/example/health/
 │   ├─ AppHealthAutoConfiguration.java
 │   ├─ config/AppHealthProperties.java
 │   ├─ health/DatabaseHealthIndicator.java
 │   ├─ health/KafkaHealthIndicator.java
 │   ├─ health/ExternalServiceHealthIndicator.java
 │   └─ util/LatencyLogger.java
 └─ src/main/resources/META-INF/spring/
     └─ org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### Key Snippets

`AppHealthAutoConfiguration`

```java
@AutoConfiguration
@EnableConfigurationProperties(AppHealthProperties.class)
@ConditionalOnProperty(prefix = "app.health", name = "enabled", havingValue = "true")
public class AppHealthAutoConfiguration {

  @Bean(name = "custom")
  HealthContributor customHealthContributor(Optional<DatabaseHealthIndicator> db,
                                            Optional<KafkaHealthIndicator> kafka,
                                            ObjectProvider<ExternalServiceHealthIndicator> externals) {
    Map<String, HealthContributor> components = new LinkedHashMap<>();
    db.ifPresent(it -> components.put("db", it));
    kafka.ifPresent(it -> components.put("kafka", it));
    Map<String, HealthIndicator> externalIndicators = new LinkedHashMap<>();
    externals.orderedStream().forEach(ind -> externalIndicators.put(ind.getName(), ind));
    if (!externalIndicators.isEmpty()) {
      components.put("external", CompositeHealthContributor.fromMap(externalIndicators));
    }
    return CompositeHealthContributor.fromMap(components);
  }

  @Bean
  @ConditionalOnProperty(prefix = "app.health.db", name = "enabled", havingValue = "true", matchIfMissing = true)
  @ConditionalOnBean(DataSource.class)
  DatabaseHealthIndicator dbIndicator(DataSource ds, AppHealthProperties props) { return new DatabaseHealthIndicator(ds, props); }

  @Bean
  @ConditionalOnProperty(prefix = "app.health.kafka", name = "enabled", havingValue = "true")
  KafkaHealthIndicator kafkaIndicator(BeanFactory beanFactory, AppHealthProperties props) { return new KafkaHealthIndicator(beanFactory, props); }

  @Bean
  ApplicationRunner startupLogger(LatencyLogger logger, ObjectProvider<HealthContributor> contributorProvider, AppHealthProperties props) {
    return args -> { if (Boolean.TRUE.equals(props.getStartupLog())) logger.logOnce(contributorProvider.getIfAvailable()); };
  }
}
```

`ExternalServiceHealthIndicator` (concept)

```java
public final class ExternalServiceHealthIndicator implements HealthIndicator {
  private final String name; private final RestClient client; private final URI uri;
  public ExternalServiceHealthIndicator(String name, RestClient client, URI uri) { this.name=name; this.client=client; this.uri=uri; }
  public String getName(){ return name; }
  @Override public Health health() {
    long started = System.nanoTime();
    try {
      var resp = client.head().uri(uri).retrieve().toBodilessEntity();
      long ms = Duration.ofNanos(System.nanoTime()-started).toMillis();
      return (resp.getStatusCode().is2xxSuccessful()) ? Health.up().withDetail("status", resp.getStatusCode().value()).withDetail("latencyMs", ms).withDetail("component", "external:"+name).build()
                                                      : Health.down().withDetail("status", resp.getStatusCode().value()).withDetail("latencyMs", ms).withDetail("component", "external:"+name).build();
    } catch (Exception e) {
      long ms = Duration.ofNanos(System.nanoTime()-started).toMillis();
      return Health.down().withDetail("error", e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())).withDetail("latencyMs", ms).withDetail("component", "external:"+name).build();
    }
  }
}
```

`KafkaHealthIndicator` (concept)

```java
public final class KafkaHealthIndicator implements HealthIndicator {
  private final AdminClient admin;
  public KafkaHealthIndicator(BeanFactory bf, AppHealthProperties props) {
    this.admin = bf.getBean(props.getKafka().getAdminClientBean(), AdminClient.class);
  }
  @Override public Health health() {
    long t = System.nanoTime();
    try {
      var nodes = admin.describeCluster().nodes().get(); // rely on AdminClient config for timeouts
      long ms = Duration.ofNanos(System.nanoTime()-t).toMillis();
      return Health.up().withDetail("nodeCount", nodes.size()).withDetail("latencyMs", ms).withDetail("component", "kafka").build();
    } catch (Exception e) {
      long ms = Duration.ofNanos(System.nanoTime()-t).toMillis();
      return Health.down().withDetail("error", e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())).withDetail("latencyMs", ms).withDetail("component", "kafka").build();
    }
  }
}
```

----------------------------------------------------------------------------

## Acceptance Criteria

- When `app.health.enabled=true`, `/actuator/health/custom` appears and aggregates enabled checks with latency details.
- Database check returns `UP` against H2 with default `SELECT 1`.
- External check performs a single `HEAD` using the parent-provided `RestClient` and reports HTTP code and latency. No library-defined timeouts or retries.
- Kafka check succeeds when a valid `AdminClient` bean is provided; otherwise, absent or `DOWN` if enabled but unreachable.
- On startup with `startupLog=true`, one-off results are logged without blocking application startup.
- Exceptions during checks are logged and reflected as `DOWN` responses but never cause startup failure or throw from the health endpoint.

----------------------------------------------------------------------------

## Commands

- Build all: `mvn -q -DskipTests package`
- Run demo: `mvn -q -pl parent-app -am spring-boot:run`
- Health endpoint: `curl -s http://localhost:8080/actuator/health/custom | jq .`

----------------------------------------------------------------------------

## Notes and Caveats

- In Boot 3.x, prefer `AutoConfiguration.imports` over legacy `spring.factories`.
- If your environment blocks outbound HTTP, keep external checks disabled or point to internal hosts.
- For production, consider adding Micrometer timers (e.g., `app.health.external.latency`) — out of scope for the minimal library but straightforward to add.
