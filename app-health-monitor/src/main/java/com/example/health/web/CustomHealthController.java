package com.example.health.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MVC alias for health. If the custom composite is available, renders it; otherwise
 * mirrors the standard Actuator health (via HealthEndpoint).
 */
@RestController
public class CustomHealthController {
    private static final Logger log = LoggerFactory.getLogger(CustomHealthController.class);

    private final HealthContributor customOrNull;

    public CustomHealthController(@Qualifier("custom") ObjectProvider<HealthContributor> customProvider) {
        this.customOrNull = customProvider.getIfAvailable();
    }

    @GetMapping(value = {"/app-health/custom", "/health/custom", "/actauator/health/custom"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> customHealth() {
        if (customOrNull != null) {
            return ResponseEntity.ok(renderContributor(customOrNull));
        }
        // Fallback: redirect to standard Actuator health endpoint when custom is disabled/not present
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", "/actuator/health").build();
    }

    private Map<String, Object> renderContributor(HealthContributor contributor) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> components = new LinkedHashMap<>();

        Status overall = renderInto(components, contributor);
        root.put("status", overall.getCode());
        if (!components.isEmpty()) {
            root.put("components", components);
        }
        return root;
    }

    private Status renderInto(Map<String, Object> out, HealthContributor contributor) {
        if (contributor instanceof CompositeHealthContributor composite) {
            Status worst = Status.UP;
            Map<String, Object> nestedMap = new LinkedHashMap<>();
            for (NamedContributor<HealthContributor> child : composite) {
                Map<String, Object> childObj = new LinkedHashMap<>();
                Status childStatus = renderInto(childObj, child.getContributor());
                worst = worseOf(worst, childStatus);
                nestedMap.put(child.getName(), childObj);
            }
            out.put("status", worst.getCode());
            if (!nestedMap.isEmpty()) {
                out.put("components", nestedMap);
            }
            return worst;
        } else if (contributor instanceof HealthIndicator hi) {
            Health h = hi.health();
            out.put("status", h.getStatus().getCode());
            if (!h.getDetails().isEmpty()) {
                out.put("details", h.getDetails());
            }
            return h.getStatus();
        }
        return Status.UNKNOWN;
    }

    private Status worseOf(Status a, Status b) {
        // Simple severity ordering: DOWN > OUT_OF_SERVICE > UNKNOWN > UP
        int ra = rank(a), rb = rank(b);
        return (rb > ra) ? b : a;
    }

    private int rank(Status s) {
        if (Status.DOWN.equals(s)) return 4;
        if (Status.OUT_OF_SERVICE.equals(s)) return 3;
        if (Status.UNKNOWN.equals(s)) return 2;
        return 1; // UP and any other
    }
}
