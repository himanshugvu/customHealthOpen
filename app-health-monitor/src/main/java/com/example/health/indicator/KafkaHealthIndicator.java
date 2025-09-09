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
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.up()
                    .withDetail("latencyMs", ms)
                    .withDetail("component", "kafka")
                    .withDetail("type", "kafka")
                    .withDetail("nodeCount", nodes.size())
                    .build();
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.down()
                    .withDetail("latencyMs", ms)
                    .withDetail("component", "kafka")
                    .withDetail("type", "kafka")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
