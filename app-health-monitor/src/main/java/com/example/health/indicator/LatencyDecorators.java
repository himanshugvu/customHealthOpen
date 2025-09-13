package com.example.health.indicator;

import org.springframework.boot.actuate.health.*;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LatencyDecorators {
    private LatencyDecorators() {}

    public static HealthIndicator withLatency(HealthIndicator delegate) {
        return () -> {
            long start = System.nanoTime();
            Health h = delegate.health();
            long ms = (System.nanoTime() - start) / 1_000_000;
            Health.Builder b = Health.status(h.getStatus());
            // Preserve all details
            b.withDetails(h.getDetails());
            // Add latency
            b.withDetail("latencyMs", ms);
            return b.build();
        };
    }

    public static HealthContributor withLatency(HealthContributor contributor) {
        if (contributor instanceof HealthIndicator hi) {
            return withLatency(hi);
        }
        if (contributor instanceof CompositeHealthContributor ch) {
            Map<String, HealthContributor> wrapped = new LinkedHashMap<>();
            ch.stream().forEach(named -> {
                HealthContributor hc = named.getContributor();
                wrapped.put(named.getName(), withLatency(hc));
            });
            return CompositeHealthContributor.fromMap(wrapped);
        }
        return contributor;
    }
}

