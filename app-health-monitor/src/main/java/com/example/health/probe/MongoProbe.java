package com.example.health.probe;

@FunctionalInterface
public interface MongoProbe {
    Result probe() throws Exception;

    record Result(String databaseName, String firstCollection) {}
}

