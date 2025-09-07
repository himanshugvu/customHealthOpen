package com.example.health.indicator;

import org.springframework.boot.actuate.health.*;

import java.util.*;

public class FlatSummaryHealthIndicator implements HealthIndicator {
    private final HealthContributor root;

    public FlatSummaryHealthIndicator(HealthContributor root) {
        this.root = root;
    }

    @Override
    public Health health() {
        List<Map<String, Object>> items = new ArrayList<>();
        boolean anyDown = flatten("", root, items);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("component", "flat");
        details.put("items", items);
        return (anyDown ? Health.down() : Health.up()).withDetails(details).build();
    }

    private boolean flatten(String prefix, HealthContributor contributor, List<Map<String, Object>> items) {
        boolean anyDown = false;
        if (contributor instanceof CompositeHealthContributor comp) {
            for (NamedContributor<HealthContributor> child : comp) {
                String name = prefix.isEmpty() ? child.getName() : prefix + "." + child.getName();
                anyDown |= flatten(name, child.getContributor(), items);
            }
        } else if (contributor instanceof HealthIndicator hi) {
            Health h = hi.health();
            String status = h.getStatus().getCode();
            Object type = h.getDetails().getOrDefault("type", inferType(prefix));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", prefix);
            m.put("type", type);
            m.put("status", status);
            if (h.getDetails().containsKey("latencyMs")) m.put("latencyMs", h.getDetails().get("latencyMs"));
            if (h.getDetails().containsKey("route")) m.put("route", h.getDetails().get("route"));
            if (h.getDetails().containsKey("nodeCount")) m.put("nodeCount", h.getDetails().get("nodeCount"));
            if (h.getDetails().containsKey("firstCollection")) m.put("firstCollection", h.getDetails().get("firstCollection"));
            if (h.getDetails().containsKey("error")) m.put("error", h.getDetails().get("error"));
            items.add(m);
            anyDown |= !Status.UP.equals(h.getStatus());
        }
        return anyDown;
    }

    private String inferType(String name) {
        if (name.startsWith("db")) return "database";
        if (name.startsWith("kafka")) return "kafka";
        if (name.startsWith("mongo")) return "mongo";
        if (name.startsWith("external")) return "external";
        if (name.startsWith("endpoints")) return "endpoints";
        return "custom";
    }
}

