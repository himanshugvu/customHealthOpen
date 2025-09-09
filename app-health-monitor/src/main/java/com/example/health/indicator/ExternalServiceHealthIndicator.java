package com.example.health.indicator;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
            // Prefer HEAD, fallback to GET then OPTIONS on 405 to accommodate strict servers
            String methodUsed = "HEAD";
            ResponseEntity<Void> resp;
            try {
                resp = restClient.head().uri(uri).retrieve().toBodilessEntity();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode() != null && e.getStatusCode().value() == 405) {
                    try {
                        methodUsed = "GET";
                        resp = restClient.get().uri(uri).retrieve().toBodilessEntity();
                    } catch (RestClientResponseException ex) {
                        if (ex.getStatusCode() != null && ex.getStatusCode().value() == 405) {
                            methodUsed = "OPTIONS";
                            resp = restClient.options().uri(uri).retrieve().toBodilessEntity();
                        } else {
                            throw ex;
                        }
                    }
                } else {
                    throw e;
                }
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            int code = resp.getStatusCode().value();
            if (code >= 200 && code < 300) {
                return Health.up()
                        .withDetail("component", "external:" + name)
                        .withDetail("type", "external")
                        .withDetail("route", uri.toString())
                        .withDetail("method", methodUsed)
                        .withDetail("status", code)
                        .withDetail("latencyMs", ms)
                        .build();
            }
            return Health.down()
                    .withDetail("component", "external:" + name)
                    .withDetail("type", "external")
                    .withDetail("route", uri.toString())
                    .withDetail("method", methodUsed)
                    .withDetail("status", code)
                    .withDetail("latencyMs", ms)
                    .build();
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.down()
                    .withDetail("component", "external:" + name)
                    .withDetail("type", "external")
                    .withDetail("route", uri.toString())
                    .withDetail("errorKind", e.getClass().getSimpleName())
                    .withDetail("error", e.getMessage())
                    .withDetail("latencyMs", ms)
                    .build();
        }
    }
}
