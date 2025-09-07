package com.example.parentapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class ExternalUrlConfig {
    @Bean(name = "externalServiceUrl")
    public URI externalServiceUrl(@Value("${demo.external.url:http://localhost:8090/status/200}") String url) {
        return URI.create(url);
    }
}

