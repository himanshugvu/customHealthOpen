package com.example.health.probe;

@FunctionalInterface
public interface DatabaseProbe {
    Result probe() throws Exception;

    record Result(String product, String version) {}
}

