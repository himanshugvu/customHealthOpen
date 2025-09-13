package com.example.health.indicator;

import com.example.health.probe.KafkaProbe;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public class KafkaHealthIndicator implements HealthIndicator {
    private final KafkaProbe probe;

    public KafkaHealthIndicator(KafkaProbe probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            KafkaProbe.Result r = probe.probe();
            if (r == null) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                return Health.down()
                        .withDetail("component", "kafka")
                        .withDetail("type", "kafka")
                        .withDetail("latencyMs", ms)
                        .withDetail("error", "nullResult")
                        .build();
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.up()
                    .withDetail("component", "kafka")
                    .withDetail("type", "kafka")
                    .withDetail("latencyMs", ms)
                    .withDetail("nodeCount", r.nodeCount())
                    .withDetail("clusterId", r.clusterId())
                    .build();
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.down()
                    .withDetail("component", "kafka")
                    .withDetail("type", "kafka")
                    .withDetail("latencyMs", ms)
                    .withDetail("errorKind", e.getClass().getSimpleName())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
