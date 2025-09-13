package com.example.health.probe.impl;

import com.example.health.probe.DatabaseProbe;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class DefaultDatabaseProbe implements DatabaseProbe {
    private final DataSource dataSource;
    private final String validationQuery;

    public DefaultDatabaseProbe(DataSource dataSource, String validationQuery) {
        this.dataSource = dataSource;
        this.validationQuery = validationQuery;
    }

    @Override
    public Result probe() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // Try a simple query; drivers may optimize validity checks otherwise
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(2); // seconds
                try (ResultSet rs = st.executeQuery(validationQuery)) {
                    // Ensure we touch result set
                    rs.next();
                }
            }
            var md = conn.getMetaData();
            String product = md != null ? md.getDatabaseProductName() : null;
            String version = md != null ? md.getDatabaseProductVersion() : null;
            return new Result(product, version);
        }
    }
}

