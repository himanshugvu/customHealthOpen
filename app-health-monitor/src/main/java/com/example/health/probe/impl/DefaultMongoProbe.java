package com.example.health.probe.impl;

import com.example.health.probe.MongoProbe;
import com.mongodb.client.MongoIterable;
import org.springframework.data.mongodb.core.MongoTemplate;

public class DefaultMongoProbe implements MongoProbe {
    private final MongoTemplate mongoTemplate;
    private final String databaseOverride;

    public DefaultMongoProbe(MongoTemplate mongoTemplate, String databaseOverride) {
        this.mongoTemplate = mongoTemplate;
        this.databaseOverride = databaseOverride;
    }

    @Override
    public Result probe() throws Exception {
        var db = (databaseOverride != null && !databaseOverride.isBlank())
                ? mongoTemplate.getMongoDatabaseFactory().getMongoDatabase(databaseOverride)
                : mongoTemplate.getDb();

        String dbName = db.getName();
        MongoIterable<String> names = db.listCollectionNames();
        String first = names.first(); // forces a round-trip
        return new Result(dbName, first);
    }
}

