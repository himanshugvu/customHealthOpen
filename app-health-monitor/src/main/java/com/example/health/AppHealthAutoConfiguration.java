package com.example.health;

import com.example.health.config.AppHealthProperties;
import com.example.health.indicator.DatabaseHealthIndicator;
import com.example.health.indicator.ExternalServiceHealthIndicator;
import com.example.health.indicator.KafkaHealthIndicator;
import com.example.health.indicator.MongoCustomHealthIndicator;
import com.example.health.indicator.EndpointsHealthIndicator;
import org.apache.kafka.clients.admin.AdminClient;
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

import javax.sql.DataSource;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;

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
            ObjectProvider<AdminClient> adminClientProvider,
            ObjectProvider<RestClient> restClientProvider
    ) {
        Map<String, HealthContributor> components = new LinkedHashMap<>();

        // DB
        if (props.getDb().isEnabled()) {
            DataSource ds = dataSourceProvider.getIfAvailable();
            if (ds != null) {
                components.put("db", (new DatabaseHealthIndicator(ds, props.getDb().getValidationQuery(), props.getDb().getType())));
            }
        }

        // Mongo (custom strategy: listCollectionNames)
        if (props.getMongo().isEnabled()) {
            MongoTemplate mt = mongoTemplateProvider.getIfAvailable();
            if (mt != null) {
                components.put("mongo", new MongoCustomHealthIndicator(mt, props.getMongo().getDatabase()));
            }
        }

        // Kafka
        if (props.getKafka().isEnabled() && StringUtils.hasText(props.getKafka().getAdminClientBean())) {
            try {
                AdminClient adminClient = (AdminClient) ctx.getBean(props.getKafka().getAdminClientBean());
                components.put("kafka", new KafkaHealthIndicator(adminClient));
            } catch (Exception e) {
                log.warn("Kafka AdminClient bean '{}' not found: {}", props.getKafka().getAdminClientBean(), e.getMessage());
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
                log.warn("External service '{}' not wired: {}", svc.getName(), e.getMessage());
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

    @Bean
    @ConditionalOnProperty(prefix = "app.health", name = "startupLog", havingValue = "true", matchIfMissing = true)
    public org.springframework.boot.ApplicationRunner appHealthStartupLogger(@org.springframework.beans.factory.annotation.Qualifier("custom") HealthContributor custom) {
        return args -> logContributorRecursive("custom", custom);
    }

    private void logContributorRecursive(String path, HealthContributor contributor) {
        if (contributor instanceof CompositeHealthContributor composite) {
            composite.stream().forEach(named -> {
                String next = path.equals("custom") ? named.getName() : path + "." + named.getName();
                logContributorRecursive(next, named.getContributor());
            });
        } else if (contributor instanceof org.springframework.boot.actuate.health.HealthIndicator hi) {
            var h = hi.health();
            Object ms = h.getDetails().getOrDefault("latencyMs", "");
            log.info("[AppHealth] {}: {}{}", path, h.getStatus(), (ms == null || ms.toString().isEmpty()) ? "" : (" in " + ms + "ms"));
        }
    }
}
