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
        long start = System.nanoTime();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(validationQuery)) {
            boolean ok = rs.next();
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ok) {
                return Health.up().withDetail("latencyMs", ms).withDetail("component", "database").withDetail("type", dbType).build();
            }
            return Health.down().withDetail("latencyMs", ms).withDetail("component", "database").withDetail("type", dbType).withDetail("error", "No result").build();
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return Health.down().withDetail("latencyMs", ms).withDetail("component", "database").withDetail("type", dbType).withDetail("error", e.getMessage()).build();
        }
    }
}
