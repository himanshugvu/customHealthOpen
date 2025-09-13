package com.example.health.probe.impl;

import com.example.health.probe.MongoProbe;

import java.lang.reflect.Method;

public class ReflectiveMongoProbe implements MongoProbe {
    private final Object backendBean;
    private final String databaseOverride;
    private final Backend backend;

    public enum Backend { MONGO_TEMPLATE, MONGO_CLIENT }

    private ReflectiveMongoProbe(Object backendBean, String databaseOverride, Backend backend) {
        this.backendBean = backendBean;
        this.databaseOverride = databaseOverride;
        this.backend = backend;
    }

    public static ReflectiveMongoProbe fromMongoTemplate(Object mongoTemplate, String dbOverride) {
        return new ReflectiveMongoProbe(mongoTemplate, dbOverride, Backend.MONGO_TEMPLATE);
    }

    public static ReflectiveMongoProbe fromMongoClient(Object mongoClient, String dbOverride) {
        return new ReflectiveMongoProbe(mongoClient, dbOverride, Backend.MONGO_CLIENT);
    }

    @Override
    public Result probe() throws Exception {
        Object mongoDatabase;
        if (backend == Backend.MONGO_TEMPLATE) {
            if (databaseOverride != null && !databaseOverride.isBlank()) {
                // template.getMongoDatabaseFactory().getMongoDatabase(db)
                Object factory = invoke(backendBean, "getMongoDatabaseFactory");
                mongoDatabase = invoke(factory, "getMongoDatabase", new Class[]{String.class}, new Object[]{databaseOverride});
            } else {
                // template.getDb()
                mongoDatabase = invoke(backendBean, "getDb");
            }
        } else { // MONGO_CLIENT
            String db = (databaseOverride != null && !databaseOverride.isBlank()) ? databaseOverride : "admin";
            mongoDatabase = invoke(backendBean, "getDatabase", new Class[]{String.class}, new Object[]{db});
        }

        String dbName = String.valueOf(invoke(mongoDatabase, "getName"));
        Object iterable = invoke(mongoDatabase, "listCollectionNames");
        String first = null;
        try {
            Object f = invoke(iterable, "first");
            first = (f == null) ? null : String.valueOf(f);
        } catch (NoSuchMethodException ignore) {
            // Fallback: iterator().hasNext() ? next() : null
            Object it = invoke(iterable, "iterator");
            boolean hasNext = (Boolean) invoke(it, "hasNext");
            if (hasNext) {
                Object n = invoke(it, "next");
                first = (n == null) ? null : String.valueOf(n);
            }
        }

        return new Result(dbName, first);
    }

    private Object invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    private Object invoke(Object target, String method, Class<?>[] paramTypes, Object[] args) throws Exception {
        Method m = target.getClass().getMethod(method, paramTypes);
        return m.invoke(target, args);
    }
}

