package com.example.health.probe;

/**
 * SPI to decouple the library from Kafka clients. Parent apps provide a bean
 * implementing this interface if they want Kafka health.
 */
@FunctionalInterface
public interface KafkaProbe {
    Result probe() throws Exception;

    record Result(Integer nodeCount, String clusterId) {}
}

