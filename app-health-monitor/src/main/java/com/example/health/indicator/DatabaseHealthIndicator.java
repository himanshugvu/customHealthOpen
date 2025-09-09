package com.example.health.indicator;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class DatabaseHealthIndicator implements HealthIndicator {
    private final DataSource dataSource;
    private final String validationQuery;
    private final String dbType;

    public DatabaseHealthIndicator(DataSource dataSource, String validationQuery, String dbType) {
        this.dataSource = dataSource;
        this.validationQuery = validationQuery;
        this.dbType = dbType;
    }

    @Override
    public Health health() {
        long t0 = System.nanoTime();
        try (Connection conn = dataSource.getConnection()) {
            long poolWaitMs = (System.nanoTime() - t0) / 1_000_000;

            // Fast connection validity check (drivers may or may not round-trip)
            boolean valid = false;
            try {
                valid = conn.isValid(2); // seconds
            } catch (Exception ignore) {
                // Some drivers may throw or not support; continue to query
            }

            conn.setReadOnly(true);
            long q0 = System.nanoTime();
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(2); // seconds
                try (ResultSet rs = st.executeQuery(validationQuery)) {
                    boolean ok = rs.next();
                    long queryMs = (System.nanoTime() - q0) / 1_000_000;
                    long totalMs = (System.nanoTime() - t0) / 1_000_000;
                    if (ok && (valid || queryMs >= 0)) {
                        return Health.up()
                                .withDetail("component", "database")
                                .withDetail("type", dbType)
                                .withDetail("latencyMs", totalMs)
                                .withDetail("poolWaitMs", poolWaitMs)
                                .withDetail("queryMs", queryMs)
                                .build();
                    }
                    return Health.down()
                            .withDetail("component", "database")
                            .withDetail("type", dbType)
                            .withDetail("latencyMs", totalMs)
                            .withDetail("poolWaitMs", poolWaitMs)
                            .withDetail("queryMs", queryMs)
                            .withDetail("error", "Validation returned no row")
                            .build();
                }
            }
        } catch (Exception e) {
            long totalMs = (System.nanoTime() - t0) / 1_000_000;
            return Health.down()
                    .withDetail("component", "database")
                    .withDetail("type", dbType)
                    .withDetail("latencyMs", totalMs)
                    .withDetail("errorKind", e.getClass().getSimpleName())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
