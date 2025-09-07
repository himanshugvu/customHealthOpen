package com.example.parentapp.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Properties;

@Configuration
public class ClientConfig {

    @Value("${demo.kafka.bootstrapServers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public HttpClientConnectionManager connectionManager() {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(20)
                .build();
    }

    @Bean
    public CloseableHttpClient httpClient(HttpClientConnectionManager cm) {
        return HttpClients.custom().setConnectionManager(cm).evictExpiredConnections().build();
    }

    @Bean(name = "myRestClient")
    public RestClient myRestClient(CloseableHttpClient httpClient) {
        return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)).build();
    }

    @Bean(name = "kafkaAdminClient")
    @ConditionalOnProperty(prefix = "demo.kafka", name = "enabled", havingValue = "true")
    public AdminClient kafkaAdminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "parent-app-admin");
        return AdminClient.create(props);
    }
}
