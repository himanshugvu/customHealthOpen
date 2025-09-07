package com.example.health.indicator;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URI;

public class ExternalServiceHealthIndicator implements HealthIndicator {
    private final RestClient restClient;
    private final URI uri;
    private final String name;

    public ExternalServiceHealthIndicator(String name, RestClient restClient, URI uri) {
        this.name = name;
        this.restClient = restClient;
        this.uri = uri;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            ResponseEntity<Void> resp = restClient.head().uri(uri).retrieve().toBodilessEntity();
            long ms = (System.nanoTime() - start) / 1_000_000;
            int code = resp.getStatusCode().value();
            if (code >= 200 && code < 300) {
                return Health.up().withDetail("component", "external:" + name).withDetail("type", "external").withDetail("route", uri.toString()).withDetail("status", code).withDetail("latencyMs", ms).build();
            }
            return Health.down().withDetail("component", "external:" + name).withDetail("type", "external").withDetail("route", uri.toString()).withDetail("status", code).withDetail("latencyMs", ms).build();
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.down().withDetail("component", "external:" + name).withDetail("type", "external").withDetail("route", uri.toString()).withDetail("error", e.getMessage()).withDetail("latencyMs", ms).build();
        }
    }
}
