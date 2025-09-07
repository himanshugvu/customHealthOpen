package com.example.health.indicator;

import com.example.health.config.AppHealthProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

public class EndpointsHealthIndicator implements HealthIndicator {
    private final RequestMappingHandlerMapping mapping;
    private final AppHealthProperties.Endpoints props;
    private final RestClient restClient; // optional

    public EndpointsHealthIndicator(RequestMappingHandlerMapping mapping, AppHealthProperties.Endpoints props, RestClient restClient) {
        this.mapping = mapping;
        this.props = props;
        this.restClient = restClient;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        List<Map<String, Object>> endpoints = mapping.getHandlerMethods().entrySet().stream()
                .filter(e -> include(e.getValue(), e.getKey()))
                .map(e -> toDescriptor(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(m -> m.get("pattern").toString()))
                .collect(Collectors.toList());

        List<Map<String, Object>> limited = endpoints;
        if (props.getMaxList() != null && endpoints.size() > props.getMaxList()) {
            limited = endpoints.subList(0, props.getMaxList());
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("component", "endpoints");
        details.put("count", endpoints.size());
        details.put("items", limited);

        // Optional probes
        List<Map<String, Object>> probeResults = new ArrayList<>();
        boolean probeFailed = false;
        if (restClient != null && props.getProbeBaseUrl() != null && props.getProbePaths() != null) {
            for (String path : props.getProbePaths()) {
                long ps = System.nanoTime();
                try {
                    var uri = UriComponentsBuilder.fromUriString(props.getProbeBaseUrl()).path(path).build().toUri();
                    Integer code = executeProbeWithFallback(uri);
                    long pms = (System.nanoTime() - ps) / 1_000_000;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("path", path);
                    r.put("status", code);
                    r.put("latencyMs", pms);
                    probeResults.add(r);
                    if (code == null || code < 200 || code >= 300) probeFailed = true;
                } catch (Exception ex) {
                    long pms = (System.nanoTime() - ps) / 1_000_000;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("path", path);
                    r.put("error", ex.getMessage());
                    r.put("latencyMs", pms);
                    probeResults.add(r);
                    probeFailed = true;
                }
            }
        }
        if (!probeResults.isEmpty()) {
            details.put("probes", probeResults);
        }

        long ms = (System.nanoTime() - start) / 1_000_000;
        details.put("latencyMs", ms);

        return (probeFailed ? Health.down() : Health.up()).withDetail("type", "endpoints").withDetails(details).build();
    }

    private Integer executeProbeWithFallback(java.net.URI uri) {
        String method = props.getProbeMethod() == null ? "HEAD" : props.getProbeMethod().toUpperCase(Locale.ROOT);
        try {
            return executeProbe(method, uri);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            if (e.getStatusCode().value() == 405) {
                if (props.isAllowGetFallback() && !"GET".equals(method)) {
                    try { return executeProbe("GET", uri); } catch (org.springframework.web.client.RestClientResponseException ex) {
                        if (ex.getStatusCode().value() != 405) throw ex; // fall through
                    }
                }
                if (props.isAllowOptionsFallback() && !"OPTIONS".equals(method)) {
                    return executeProbe("OPTIONS", uri);
                }
            }
            throw e;
        }
    }

    private Integer executeProbe(String method, java.net.URI uri) {
        return switch (method) {
            case "HEAD" -> restClient.head().uri(uri).retrieve().toBodilessEntity().getStatusCode().value();
            case "GET" -> restClient.get().uri(uri).retrieve().toBodilessEntity().getStatusCode().value();
            case "OPTIONS" -> restClient.options().uri(uri).retrieve().toBodilessEntity().getStatusCode().value();
            default -> throw new IllegalArgumentException("Unsupported probe method: " + method);
        };
    }

    private boolean include(HandlerMethod handler, RequestMappingInfo info) {
        boolean isError = handler.getBeanType().getName().contains("BasicErrorController");
        boolean isActuator = info.getPathPatternsCondition() != null &&
                info.getPathPatternsCondition().getPatterns().stream().anyMatch(p -> p.getPatternString().startsWith("/actuator"));
        if (isError && !props.isIncludeError()) return false;
        if (isActuator && !props.isIncludeActuator()) return false;
        return true;
    }

    private Map<String, Object> toDescriptor(RequestMappingInfo info, HandlerMethod method) {
        var patterns = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatterns().stream().map(Object::toString).collect(Collectors.toSet())
                : Collections.<String>emptySet();
        var methods = info.getMethodsCondition().getMethods().stream().map(Enum::name).collect(Collectors.toSet());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pattern", patterns.isEmpty() ? "" : String.join(",", patterns));
        m.put("methods", methods);
        m.put("handler", method.getBeanType().getSimpleName() + "." + method.getMethod().getName());
        return m;
    }
}
