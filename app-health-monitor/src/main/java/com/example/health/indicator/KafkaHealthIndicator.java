package com.example.health.indicator;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;


public class KafkaHealthIndicator implements HealthIndicator {
    private final AdminClient adminClient;

    public KafkaHealthIndicator(AdminClient adminClient) {
        this.adminClient = adminClient;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            var desc = adminClient.describeCluster();
            var nodes = desc.nodes().get();
            String clusterId = null;
            try { clusterId = desc.clusterId().get(); } catch (Exception ignore) { }
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.up()
                    .withDetail("component", "kafka")
                    .withDetail("type", "kafka")
                    .withDetail("latencyMs", ms)
                    .withDetail("nodeCount", nodes.size())
                    .withDetail("clusterId", clusterId)
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
