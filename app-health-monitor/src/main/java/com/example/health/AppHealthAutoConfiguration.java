package com.example.health;

import com.example.health.config.AppHealthProperties;
import com.example.health.indicator.DatabaseHealthIndicator;
import com.example.health.indicator.ExternalServiceHealthIndicator;
import com.example.health.indicator.KafkaHealthIndicator;
import com.example.health.probe.KafkaProbe;
import com.example.health.indicator.MongoHealthIndicator;
import com.example.health.indicator.EndpointsHealthIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import java.util.concurrent.*;
import java.util.*;

import javax.sql.DataSource;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.example.health.probe.DatabaseProbe;
import com.example.health.probe.MongoProbe;
import com.example.health.probe.impl.DefaultDatabaseProbe;
import com.example.health.probe.impl.DefaultMongoProbe;

@AutoConfiguration
@EnableConfigurationProperties(AppHealthProperties.class)
@ConditionalOnProperty(prefix = "app.health", name = "enabled", havingValue = "true")
public class AppHealthAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AppHealthAutoConfiguration.class);

    @Bean(name = "custom")
    @ConditionalOnMissingBean(name = "custom")
    public HealthContributor customHealthContributor(
            ApplicationContext ctx,
            ConversionService conversionService,
            AppHealthProperties props,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<MongoTemplate> mongoTemplateProvider,
            ObjectProvider<RestClient> restClientProvider
        ) {
        Map<String, HealthContributor> components = new LinkedHashMap<>();

        // DB via SPI probe with fallback to DataSource
        if (props.getDb().isEnabled()) {
            try {
                DatabaseProbe dbProbe;
                if (StringUtils.hasText(props.getDb().getProbeBean())) {
                    dbProbe = (DatabaseProbe) ctx.getBean(props.getDb().getProbeBean());
                } else {
                    dbProbe = getBeanSafely(ctx, DatabaseProbe.class);
                    if (dbProbe == null) {
                        DataSource ds = dataSourceProvider.getIfAvailable();
                        if (ds != null) {
                            dbProbe = new DefaultDatabaseProbe(ds, props.getDb().getValidationQuery());
                        }
                    }
                }
                if (dbProbe != null) {
                    components.put("db", new DatabaseHealthIndicator(dbProbe, props.getDb().getType()));
                }
            } catch (Exception e) {
                log.atWarn()
                        .addKeyValue("event", "db_probe_missing")
                        .addKeyValue("msg", "DatabaseProbe-not-available")
                        .addKeyValue("errorKind", e.getClass().getSimpleName())
                        .addKeyValue("error", sanitize(e.getMessage()))
                        .log("db probe missing");
            }
        }

        // Mongo via SPI probe with fallback to MongoTemplate
        if (props.getMongo().isEnabled()) {
            try {
                MongoProbe mongoProbe;
                if (StringUtils.hasText(props.getMongo().getProbeBean())) {
                    mongoProbe = (MongoProbe) ctx.getBean(props.getMongo().getProbeBean());
                } else {
                    mongoProbe = getBeanSafely(ctx, MongoProbe.class);
                    if (mongoProbe == null) {
                        MongoTemplate mt = mongoTemplateProvider.getIfAvailable();
                        if (mt != null) {
                            mongoProbe = new DefaultMongoProbe(mt, props.getMongo().getDatabase());
                        }
                    }
                }
                if (mongoProbe != null) {
                    components.put("mongo", new MongoHealthIndicator(mongoProbe));
                }
            } catch (Exception e) {
                log.atWarn()
                        .addKeyValue("event", "mongo_probe_missing")
                        .addKeyValue("msg", "MongoProbe-not-available")
                        .addKeyValue("errorKind", e.getClass().getSimpleName())
                        .addKeyValue("error", sanitize(e.getMessage()))
                        .log("mongo probe missing");
            }
        }

        // Kafka via SPI probe (no hard Kafka dependency)
        if (props.getKafka().isEnabled()) {
            try {
                KafkaProbe probe;
                if (StringUtils.hasText(props.getKafka().getProbeBean())) {
                    probe = (KafkaProbe) ctx.getBean(props.getKafka().getProbeBean());
                } else {
                    probe = ctx.getBean(KafkaProbe.class);
                }
                components.put("kafka", new KafkaHealthIndicator(probe));
            } catch (Exception e) {
                log.atWarn()
                        .addKeyValue("event", "kafka_probe_missing")
                        .addKeyValue("msg", "KafkaProbe-not-available")
                        .addKeyValue("errorKind", e.getClass().getSimpleName())
                        .addKeyValue("error", sanitize(e.getMessage()))
                        .log("kafka probe missing");
            }
        }

        // External services
        Map<String, HealthContributor> external = new LinkedHashMap<>();
        for (AppHealthProperties.External.Service svc : props.getExternal().getServices()) {
            if (!svc.isEnabled()) continue;
            if (!StringUtils.hasText(svc.getName())) continue;
            try {
                RestClient rc = (RestClient) ctx.getBean(svc.getRestClientBean());
                Object uriBean = ctx.getBean(svc.getUrlBean());
                URI uri = convertToUri(uriBean, conversionService);
                external.put(svc.getName(), new ExternalServiceHealthIndicator(svc.getName(), rc, uri));
            } catch (Exception e) {
                log.atWarn()
                        .addKeyValue("event", "external_service_not_wired")
                        .addKeyValue("name", svc.getName())
                        .addKeyValue("errorKind", e.getClass().getSimpleName())
                        .addKeyValue("error", sanitize(e.getMessage()))
                        .log("external service not wired");
            }
        }
        if (!external.isEmpty()) {
            components.put("external", CompositeHealthContributor.fromMap(external));
        }

        // Endpoints listing + optional probes
        if (props.getEndpoints().isEnabled()) {
            RequestMappingHandlerMapping mapping;
            try {
                mapping = ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
                RestClient rc = null;
                if (props.getEndpoints().getRestClientBean() != null && !props.getEndpoints().getRestClientBean().isBlank()) {
                    rc = (RestClient) ctx.getBean(props.getEndpoints().getRestClientBean());
                }
                components.put("endpoints", new EndpointsHealthIndicator(mapping, props.getEndpoints(), rc));
            } catch (Exception ex) {
                log.warn("Endpoints mapping not available: {}", ex.getMessage());
            }
        }

        CompositeHealthContributor composite = CompositeHealthContributor.fromMap(components);
        // Add a flat summary as a child component
        Map<String, HealthContributor> withFlat = new LinkedHashMap<>(components);
        withFlat.put("flat", new com.example.health.indicator.FlatSummaryHealthIndicator(composite));
        return CompositeHealthContributor.fromMap(withFlat);
    }

    private URI convertToUri(Object bean, ConversionService conversionService) {
        if (bean instanceof URI) return (URI) bean;
        if (bean instanceof String) return URI.create((String) bean);
        if (conversionService.canConvert(bean.getClass(), URI.class)) {
            return conversionService.convert(bean, URI.class);
        }
        throw new IllegalArgumentException("Unsupported URL bean type: " + bean.getClass());
    }

    private <T> T getBeanSafely(ApplicationContext ctx, Class<T> type) {
        try {
            return ctx.getBean(type);
        } catch (Exception ex) {
            return null;
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.health", name = "startupLog", havingValue = "true", matchIfMissing = true)
    public ApplicationListener<ApplicationReadyEvent> appHealthStartupLogger(
            @org.springframework.beans.factory.annotation.Qualifier("custom") HealthContributor custom,
            AppHealthProperties props) {
        return event -> java.util.concurrent.CompletableFuture.runAsync(() ->
                logContributorsParallel(custom, props.getStartupTimeoutMs()));
    }

    private void logContributorsParallel(HealthContributor root, int timeoutMs) {
        List<Map.Entry<String, org.springframework.boot.actuate.health.HealthIndicator>> indicators = new ArrayList<>();
        collectIndicators("custom", root, indicators);

        int threads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), indicators.size()));
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            List<Map.Entry<String, CompletableFuture<org.springframework.boot.actuate.health.Health>>> futures = new ArrayList<>();
            for (var e : indicators) {
                CompletableFuture<org.springframework.boot.actuate.health.Health> f = CompletableFuture
                        .supplyAsync(() -> e.getValue().health(), exec)
                        .orTimeout(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> org.springframework.boot.actuate.health.Health.down()
                                .withDetail("errorKind", ex.getClass().getSimpleName())
                                .withDetail("error", ex.getMessage())
                                .build());
                futures.add(Map.entry(e.getKey(), f));
            }

            Status worst = Status.UP;
            for (var ef : futures) {
                var h = ef.getValue().join();
                worst = worseOf(worst, h.getStatus());
            }
            log.atInfo()
                    .addKeyValue("event", "app_health_summary")
                    .addKeyValue("status", worst.getCode())
                    .addKeyValue("components", indicators.size())
                    .log("app health summary");

            for (var ef : futures) {
                var h = ef.getValue().join();
                Object ms = h.getDetails().getOrDefault("latencyMs", "");
                var builder = log.atInfo()
                        .addKeyValue("event", "app_health_component")
                        .addKeyValue("path", ef.getKey())
                        .addKeyValue("status", h.getStatus().getCode());
                if (ms != null && !ms.toString().isEmpty()) {
                    builder.addKeyValue("latencyMs", ms);
                }
                builder.log("app health component");
            }
        } finally {
            exec.shutdown();
        }
    }

    private void collectIndicators(String path, HealthContributor contributor,
                                   List<Map.Entry<String, org.springframework.boot.actuate.health.HealthIndicator>> out) {
        if (contributor instanceof CompositeHealthContributor composite) {
            composite.stream().forEach(named -> {
                String next = path.equals("custom") ? named.getName() : path + "." + named.getName();
                collectIndicators(next, named.getContributor(), out);
            });
        } else if (contributor instanceof org.springframework.boot.actuate.health.HealthIndicator hi) {
            out.add(Map.entry(path, hi));
        }
    }

    private Status worseOf(Status a, Status b) {
        int ra = rank(a), rb = rank(b);
        return (rb > ra) ? b : a;
    }

    private int rank(Status s) {
        if (Status.DOWN.equals(s)) return 4;
        if (Status.OUT_OF_SERVICE.equals(s)) return 3;
        if (Status.UNKNOWN.equals(s)) return 2;
        return 1;
    }

    private String sanitize(String msg) {
        if (msg == null) return "";
        return msg.replaceAll("\\s+", "_");
    }
}
