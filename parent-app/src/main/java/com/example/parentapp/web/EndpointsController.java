package com.example.parentapp.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;
import java.util.stream.Collectors;

@RestController
public class EndpointsController {

    private final RequestMappingHandlerMapping mappings;

    public EndpointsController(@org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    @GetMapping(value = "/demo/endpoints", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> listEndpoints() {
        return mappings.getHandlerMethods().entrySet().stream()
                .filter(e -> !isInternal(e.getValue(), e.getKey()))
                .map(e -> toDescriptor(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(m -> m.get("pattern").toString()))
                .collect(Collectors.toList());
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

    private boolean isInternal(HandlerMethod handler, RequestMappingInfo info) {
        String bean = handler.getBeanType().getName();
        boolean isError = bean.contains("BasicErrorController");
        boolean isActuator = info.getPathPatternsCondition() != null &&
                info.getPathPatternsCondition().getPatterns().stream().anyMatch(p -> p.getPatternString().startsWith("/actuator"));
        return isError || isActuator;
    }
}
