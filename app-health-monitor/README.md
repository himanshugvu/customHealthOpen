# app-health-monitor

Drop‑in health monitoring for Spring Boot apps with:
- Zero hard dependency on DB/Mongo/Kafka clients in the library
- Prefer Spring Boot Actuator built‑in checks where available
- Optional SPI probes for Kafka/DB/Mongo
- Parallel startup summary logging with structured fields and latencyMs

## Quick Start

Add the module to your build and enable it:

```
app.health.enabled=true
app.health.startupLog=true
# optional per‑indicator timeout for startup logging
app.health.startupTimeoutMs=2000

# Optional: render structured key=val logs in console
logging.structured.format.console=logfmt
```

Expose health details if you want to see per‑component fields on the endpoint:

```
management.endpoint.health.show-details=always
```

## What gets checked

- Database (`db`)
  - Preferred: Spring Boot Actuator `db` contributor if your app has a `DataSource`.
  - Fallback: a lightweight `DatabaseProbe` using your `DataSource` (if present) and a simple validation query.
  - Details include `latencyMs` (added via a decorator when using Actuator).

- Mongo (`mongo`)
  - Preferred: Spring Boot Actuator `mongo` contributor if your app has a `MongoClient`/`MongoTemplate`.
  - Fallback: `MongoProbe` or a reflection‑based probe that calls `listCollectionNames` on either `MongoTemplate` or `MongoClient` if available.
  - Details include `latencyMs`.

- Kafka (`kafka`)
  - SPI only: provide a `KafkaProbe` bean in your app if you want Kafka health (no Kafka dependency in this library).

- External services (`external`) and endpoints listing/probing (`endpoints`) are also supported via properties (see below).

## Configuration

```
app.health.enabled=true
app.health.startupLog=true
app.health.startupTimeoutMs=2000

app.health.db.enabled=true
app.health.db.type=jdbc
app.health.db.validationQuery=SELECT 1
# optional bean name if you provide a custom DatabaseProbe
app.health.db.probeBean=

app.health.mongo.enabled=true
# optional override of DB name
app.health.mongo.database=
# optional bean name if you provide a custom MongoProbe
app.health.mongo.probeBean=

app.health.kafka.enabled=true
# optional bean name if you provide a custom KafkaProbe
app.health.kafka.probeBean=

app.health.external.services[0].name=httpbin
app.health.external.services[0].enabled=true
app.health.external.services[0].restClientBean=myRestClient
app.health.external.services[0].urlBean=externalServiceUrl

app.health.endpoints.enabled=true
app.health.endpoints.restClientBean=myRestClient
app.health.endpoints.probeBaseUrl=http://localhost:8080
app.health.endpoints.probePaths[0]=/demo/endpoints
```

## KafkaProbe example

This stays in your app (the library has no Kafka dependency):

```java
@Bean
KafkaProbe kafkaProbe(org.apache.kafka.clients.admin.AdminClient admin) {
    return () -> {
        var desc = admin.describeCluster();
        String clusterId = desc.clusterId().get(2, java.util.concurrent.TimeUnit.SECONDS);
        int nodes = desc.nodes().get(2, java.util.concurrent.TimeUnit.SECONDS).size();
        if (nodes <= 0) {
            throw new IllegalStateException("Kafka cluster reachable but no active brokers");
        }
        return new com.example.health.probe.KafkaProbe.Result(nodes, clusterId);
    };
}
```

## How DB and Mongo health are determined

- If your app includes the relevant Actuator auto‑configurations, the library reuses those indicators and decorates them to add `latencyMs`.
- If not, fallbacks are used:
  - DB: `DatabaseProbe` (auto‑created when a `DataSource` bean exists) runs `validationQuery`.
  - Mongo: reflection probe invokes `listCollectionNames` using either `MongoTemplate` or `MongoClient` if present.

## Structured logging

Startup logging emits structured fields via SLF4J’s fluent API. To render logfmt in console:

```
logging.structured.format.console=logfmt
```

Example lines:

```
event=app_health_summary status=UP components=4 message="app health summary"
event=app_health_component path=db status=UP latencyMs=12 message="app health component"
event=app_health_component path=mongo status=UP latencyMs=8 message="app health component"
```

## Migration notes

- Kafka property changed
  - Old: `app.health.kafka.adminClientBean` (removed)
  - New: `app.health.kafka.probeBean` (optional). Provide a `KafkaProbe` bean by type or name.

- Dependencies removed from the library
  - No compile‑time Mongo or Spring JDBC dependencies. Your app may still include them; the library auto‑detects beans reflectively.

- Latency
  - When Actuator `db`/`mongo` indicators are present, the library wraps them so the `custom` composite includes `latencyMs`.

