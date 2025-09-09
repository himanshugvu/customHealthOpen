package com.example.health.indicator;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * External service health. Tries a cheap HEAD first; when a server rejects the method (405),
 * falls back to GET, then OPTIONS. Does not mask other failures.
 */
public class ExternalServiceHealthIndicator implements HealthIndicator {
    private final RestClient restClient;
    private final URI uri;
    private final String name;

    public ExternalServiceHealthIndicator(String name, RestClient restClient, URI uri) {
        this.name = Objects.requireNonNull(name, "name");
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.uri = Objects.requireNonNull(uri, "uri");
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            ProbeResult result = probeWithFallback(uri);
            long ms = elapsedMs(start);
            boolean ok = is2xx(result.status());
            Health.Builder b = ok ? Health.up() : Health.down();
            return b.withDetail("component", "external:" + name)
                    .withDetail("type", "external")
                    .withDetail("route", uri.toString())
                    .withDetail("method", result.method())
                    .withDetail("status", result.status())
                    .withDetail("latencyMs", ms)
                    .build();
        } catch (Exception e) {
            long ms = elapsedMs(start);
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

    private ProbeResult probeWithFallback(URI uri) {
        try {
            return new ProbeResult("HEAD", statusOf(restClient.head().uri(uri).header("Accept", "*/*").retrieve().toBodilessEntity()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() != null && e.getStatusCode().value() == 405) {
                try {
                    return new ProbeResult("GET", statusOf(restClient.get().uri(uri).header("Accept", "*/*").retrieve().toBodilessEntity()));
                } catch (RestClientResponseException ex) {
                    if (ex.getStatusCode() != null && ex.getStatusCode().value() == 405) {
                        return new ProbeResult("OPTIONS", statusOf(restClient.options().uri(uri).retrieve().toBodilessEntity()));
                    }
                    throw ex;
                }
            }
            throw e;
        }
    }

    private int statusOf(ResponseEntity<Void> resp) {
        return resp.getStatusCode().value();
    }

    private boolean is2xx(int code) {
        return code >= 200 && code < 300;
    }

    private long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private record ProbeResult(String method, int status) { }
}
