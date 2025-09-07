package com.example.health.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.health")
public class AppHealthProperties {
    private boolean enabled = false;
    private boolean startupLog = true;
    private final Db db = new Db();
    private final Mongo mongo = new Mongo();
    private final Kafka kafka = new Kafka();
    private final External external = new External();
    private final Endpoints endpoints = new Endpoints();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isStartupLog() { return startupLog; }
    public void setStartupLog(boolean startupLog) { this.startupLog = startupLog; }

    public Db getDb() { return db; }
    public Mongo getMongo() { return mongo; }
    public Kafka getKafka() { return kafka; }
    public External getExternal() { return external; }
    public Endpoints getEndpoints() { return endpoints; }

    public static class Db {
        private boolean enabled = true;
        private String validationQuery = "SELECT 1";
        private String type = "jdbc"; // e.g., postgres, mysql

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getValidationQuery() { return validationQuery; }
        public void setValidationQuery(String validationQuery) { this.validationQuery = validationQuery; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    public static class Kafka {
        private boolean enabled = false;
        private String adminClientBean;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAdminClientBean() { return adminClientBean; }
        public void setAdminClientBean(String adminClientBean) { this.adminClientBean = adminClientBean; }
    }

    public static class Mongo {
        private boolean enabled = false;
        /** Strategy: list collection names on the configured database; no ping. */
        private String database = null; // null means use default from MongoTemplate

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
    }

    public static class Endpoints {
        private boolean enabled = false;
        private boolean includeActuator = false;
        private boolean includeError = false;
        private Integer maxList = 50;
        private String probeBaseUrl; // e.g. http://localhost:8080
        private String restClientBean; // optional, for probe
        private List<String> probePaths = new ArrayList<>(); // e.g. ["/demo/endpoints"]
        private String probeMethod = "HEAD"; // HEAD | GET | OPTIONS
        private boolean allowGetFallback = true;
        private boolean allowOptionsFallback = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isIncludeActuator() { return includeActuator; }
        public void setIncludeActuator(boolean includeActuator) { this.includeActuator = includeActuator; }
        public boolean isIncludeError() { return includeError; }
        public void setIncludeError(boolean includeError) { this.includeError = includeError; }
        public Integer getMaxList() { return maxList; }
        public void setMaxList(Integer maxList) { this.maxList = maxList; }
        public String getProbeBaseUrl() { return probeBaseUrl; }
        public void setProbeBaseUrl(String probeBaseUrl) { this.probeBaseUrl = probeBaseUrl; }
        public String getRestClientBean() { return restClientBean; }
        public void setRestClientBean(String restClientBean) { this.restClientBean = restClientBean; }
        public List<String> getProbePaths() { return probePaths; }
        public void setProbePaths(List<String> probePaths) { this.probePaths = probePaths; }
        public String getProbeMethod() { return probeMethod; }
        public void setProbeMethod(String probeMethod) { this.probeMethod = probeMethod; }
        public boolean isAllowGetFallback() { return allowGetFallback; }
        public void setAllowGetFallback(boolean allowGetFallback) { this.allowGetFallback = allowGetFallback; }
        public boolean isAllowOptionsFallback() { return allowOptionsFallback; }
        public void setAllowOptionsFallback(boolean allowOptionsFallback) { this.allowOptionsFallback = allowOptionsFallback; }
    }

    public static class External {
        private List<Service> services = new ArrayList<>();
        public List<Service> getServices() { return services; }
        public void setServices(List<Service> services) { this.services = services; }

        public static class Service {
            private String name;
            private boolean enabled = true;
            private String restClientBean;
            private String urlBean;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getRestClientBean() { return restClientBean; }
            public void setRestClientBean(String restClientBean) { this.restClientBean = restClientBean; }
            public String getUrlBean() { return urlBean; }
            public void setUrlBean(String urlBean) { this.urlBean = urlBean; }
        }
    }
}
