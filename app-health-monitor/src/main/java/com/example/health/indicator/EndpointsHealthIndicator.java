package com.example.health.indicator;

import com.example.health.config.AppHealthProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.web.client.RestClient;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Health indicator that summarizes application endpoints and (optionally) probes
 * a safe allowlist for basic availability. Designed to be fast and side-effect free.
 */
public class EndpointsHealthIndicator implements HealthIndicator {

    private final RequestMappingHandlerMapping mapping;
    private final AppHealthProperties.Endpoints props;
    private final RestClient restClient; // optional

    public EndpointsHealthIndicator(RequestMappingHandlerMapping mapping,
                                    AppHealthProperties.Endpoints props,
                                    RestClient restClient) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.props = Objects.requireNonNull(props, "props");
        this.restClient = restClient; // may be null by design
    }

    @Override
    public Health health() {
        long start = System.nanoTime();

        List<Map<String, Object>> descriptors = collectEndpointDescriptors();
        List<Map<String, Object>> limited = limit(descriptors, props.getMaxList());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("component", "endpoints");
        details.put("count", descriptors.size());
        details.put("items", limited);

        boolean probeFailed = false;
        List<Map<String, Object>> probeResults = Collections.emptyList();
        if (shouldProbe()) {
            probeResults = new ArrayList<>();
            for (String raw : props.getProbePaths()) {
                String path = normalizePath(raw);
                long ps = System.nanoTime();
                try {
                    URI uri = UriComponentsBuilder.fromUriString(props.getProbeBaseUrl()).path(path).build().toUri();
                    ProbeOutcome outcome = executeProbeWithFallback(uri);
                    long pms = elapsedMs(ps);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("path", path);
                    r.put("status", outcome.status());
                    r.put("method", outcome.method());
                    r.put("latencyMs", pms);
                    probeResults.add(r);
                    if (outcome.status() == null || outcome.status() < 200 || outcome.status() >= 300) probeFailed = true;
                } catch (Exception ex) {
                    long pms = elapsedMs(ps);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("path", path);
                    r.put("error", ex.getMessage());
                    r.put("errorKind", ex.getClass().getSimpleName());
                    r.put("latencyMs", pms);
                    probeResults.add(r);
                    probeFailed = true;
                }
            }
        }
        if (!probeResults.isEmpty()) {
            details.put("probes", probeResults);
        }

        details.put("latencyMs", elapsedMs(start));
        return (probeFailed ? Health.down() : Health.up())
                .withDetail("type", "endpoints")
                .withDetails(details)
                .build();
    }

    // ---- internals ----

    private List<Map<String, Object>> collectEndpointDescriptors() {
        return mapping.getHandlerMethods().entrySet().stream()
                .filter(e -> isIncludedEndpoint(e.getValue(), e.getKey()))
                .map(e -> descriptorOf(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(m -> m.get("pattern").toString()))
                .collect(Collectors.toList());
    }

    private boolean isIncludedEndpoint(HandlerMethod handler, RequestMappingInfo info) {
        boolean isError = handler.getBeanType().getName().contains("BasicErrorController");
        boolean isActuator = info.getPathPatternsCondition() != null &&
                info.getPathPatternsCondition().getPatterns().stream().anyMatch(p -> p.getPatternString().startsWith("/actuator"));
        if (isError && !props.isIncludeError()) return false;
        if (isActuator && !props.isIncludeActuator()) return false;
        return true;
    }

    private Map<String, Object> descriptorOf(RequestMappingInfo info, HandlerMethod method) {
        var patterns = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatterns().stream().map(Object::toString).collect(Collectors.toCollection(LinkedHashSet::new))
                : Collections.<String>emptySet();
        var methods = info.getMethodsCondition().getMethods().stream().map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pattern", patterns.isEmpty() ? "" : String.join(",", patterns));
        m.put("methods", methods);
        m.put("handler", method.getBeanType().getSimpleName() + "." + method.getMethod().getName());
        return m;
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> items, Integer max) {
        if (max == null || max <= 0 || items.size() <= max) return items;
        return items.subList(0, max);
    }

    private boolean shouldProbe() {
        return restClient != null && props.getProbeBaseUrl() != null && props.getProbePaths() != null && !props.getProbePaths().isEmpty();
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }

    private long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private record ProbeOutcome(Integer status, String method) {}

    private ProbeOutcome executeProbeWithFallback(URI uri) {
        String method = props.getProbeMethod() == null ? "HEAD" : props.getProbeMethod().toUpperCase(Locale.ROOT);
        try {
            return new ProbeOutcome(executeProbe(method, uri), method);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            if (e.getStatusCode().value() == 405) {
                if (props.isAllowGetFallback() && !"GET".equals(method)) {
                    try {
                        return new ProbeOutcome(executeProbe("GET", uri), "GET");
                    } catch (org.springframework.web.client.RestClientResponseException ex) {
                        if (ex.getStatusCode().value() != 405) throw ex; // fall through
                    }
                }
                if (props.isAllowOptionsFallback() && !"OPTIONS".equals(method)) {
                    return new ProbeOutcome(executeProbe("OPTIONS", uri), "OPTIONS");
                }
            }
            throw e;
        }
    }

    private Integer executeProbe(String method, URI uri) {
        return switch (method) {
            case "HEAD" -> restClient.head().uri(uri).retrieve().toBodilessEntity().getStatusCode().value();
            case "GET" -> restClient.get().uri(uri).retrieve().toBodilessEntity().getStatusCode().value();
            case "OPTIONS" -> restClient.options().uri(uri).retrieve().toBodilessEntity().getStatusCode().value();
            default -> throw new IllegalArgumentException("Unsupported probe method: " + method);
        };
    }
}
