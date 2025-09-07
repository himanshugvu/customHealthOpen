package com.example.parentapp.web;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
public class DemoController {

    @Autowired
    MongoTemplate mongoTemplate;

    @Autowired
    ObjectProvider<AdminClient> kafkaAdminClient;

    @Autowired
    RestClient myRestClient;

    @Autowired
    java.net.URI externalServiceUrl;

    @GetMapping("/demo/mongo/ping")
    public Map<String, Object> mongoPing() {
        var doc = Map.of("ts", Instant.now().toString());
        mongoTemplate.getDb().getCollection("pings").insertOne(new org.bson.Document(doc));
        long count = mongoTemplate.getDb().getCollection("pings").countDocuments();
        return Map.of("ok", true, "count", count);
    }

    @GetMapping("/demo/kafka/info")
    public Map<String, Object> kafkaInfo() throws Exception {
        AdminClient admin = kafkaAdminClient.getIfAvailable();
        if (admin == null) {
            return Map.of("error", "Kafka AdminClient not configured", "nodes", 0);
        }
        var nodes = admin.describeCluster().nodes().get(5, TimeUnit.SECONDS);
        return Map.of("nodes", nodes.size());
    }

    @GetMapping("/demo/external/ping")
    public ResponseEntity<Void> externalPing() {
        return myRestClient.get().uri(externalServiceUrl).retrieve().toBodilessEntity();
    }
}
