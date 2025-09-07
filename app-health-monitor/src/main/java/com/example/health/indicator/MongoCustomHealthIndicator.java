package com.example.health.indicator;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoIterable;
import org.bson.Document;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Mongo health using a non-ping strategy: list collection names on the DB.
 * Succeeds if the command executes (even if there are zero collections).
 */
public class MongoCustomHealthIndicator implements HealthIndicator {
    private final MongoTemplate mongoTemplate;
    private final String databaseOverride;

    public MongoCustomHealthIndicator(MongoTemplate mongoTemplate, String databaseOverride) {
        this.mongoTemplate = mongoTemplate;
        this.databaseOverride = databaseOverride;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            var db = databaseOverride != null && !databaseOverride.isBlank()
                    ? mongoTemplate.getMongoDatabaseFactory().getMongoDatabase(databaseOverride)
                    : mongoTemplate.getDb();

            MongoIterable<String> names = db.listCollectionNames();
            // Trigger a round trip
            String first = names.first();
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.up()
                    .withDetail("component", "mongo")
                    .withDetail("type", "mongo")
                    .withDetail("latencyMs", ms)
                    .withDetail("firstCollection", first)
                    .build();
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.down()
                    .withDetail("component", "mongo")
                    .withDetail("type", "mongo")
                    .withDetail("latencyMs", ms)
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
