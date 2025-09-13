package com.example.health.indicator;

import com.example.health.probe.MongoProbe;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public class MongoHealthIndicator implements HealthIndicator {
    private final MongoProbe probe;

    public MongoHealthIndicator(MongoProbe probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            MongoProbe.Result r = probe.probe();
            if (r == null) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                return Health.down()
                        .withDetail("component", "mongo")
                        .withDetail("type", "mongo")
                        .withDetail("latencyMs", ms)
                        .withDetail("error", "nullResult")
                        .build();
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.up()
                    .withDetail("component", "mongo")
                    .withDetail("type", "mongo")
                    .withDetail("latencyMs", ms)
                    .withDetail("database", r.databaseName())
                    .withDetail("firstCollection", r.firstCollection())
                    .build();
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.down()
                    .withDetail("component", "mongo")
                    .withDetail("type", "mongo")
                    .withDetail("latencyMs", ms)
                    .withDetail("errorKind", e.getClass().getSimpleName())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
