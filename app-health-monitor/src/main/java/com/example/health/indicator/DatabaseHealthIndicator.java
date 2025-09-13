package com.example.health.indicator;

import com.example.health.probe.DatabaseProbe;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public class DatabaseHealthIndicator implements HealthIndicator {
    private final DatabaseProbe probe;
    private final String dbType;

    public DatabaseHealthIndicator(DatabaseProbe probe, String dbType) {
        this.probe = probe;
        this.dbType = dbType;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            DatabaseProbe.Result r = probe.probe();
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.up()
                    .withDetail("component", "database")
                    .withDetail("type", dbType)
                    .withDetail("latencyMs", ms)
                    .withDetail("dbProduct", r.product())
                    .withDetail("dbVersion", r.version())
                    .build();
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.down()
                    .withDetail("component", "database")
                    .withDetail("type", dbType)
                    .withDetail("latencyMs", ms)
                    .withDetail("errorKind", e.getClass().getSimpleName())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
